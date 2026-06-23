package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpConnectionManager {



    private static final Path PIDS_DIR = Paths.get(System.getProperty("user.home"), ".zephyr/mcp-pids");

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @PostConstruct
    void cleanupOrphanProcesses() {
        try {
            Files.createDirectories(PIDS_DIR);
        } catch (Exception e) {
            log.warn("创建 MCP PID 目录失败: {}", PIDS_DIR, e);
            return;
        }
        File[] files = PIDS_DIR.toFile().listFiles((d, n) -> n.endsWith(".pid"));
        if (files == null || files.length == 0) {
            log.info("无孤儿 MCP 进程需要清理");
            return;
        }
        for (File f : files) {
            try {
                long pid = Long.parseLong(Files.readString(f.toPath()).trim());
                ProcessHandle.of(pid).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.destroyForcibly();
                    log.info("已清理孤儿 MCP 进程: pid={}, file={}", pid, f.getName());
                });
                Files.deleteIfExists(f.toPath());
            } catch (Exception e) {
                log.warn("清理孤儿进程失败: {}", f.getName(), e);
            }
        }
        mcpDao.resetAllServerStatus("disconnected");
        log.info("MCP 服务器状态已重置: 所有服务器设为 disconnected");
    }



    @Resource
    private McpDao mcpDao;

    public McpConnection getConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.get(key);
        if (conn != null) {
            log.debug("MCP 连接复用: user={}, serverId={}", userName, serverId);
            return conn;
        }
        return createConnection(key, userName, serverId);
    }

    private synchronized McpConnection createConnection(String key, String userName, String serverId) {
        McpConnection existing = connections.get(key);
        if (existing != null) return existing;

        if (connections.size() >= cfg.getMcp().getConnection().getMaxConnections()) {
            evictLru();
        }

        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server == null || !server.getUserName().equals(userName)) {
            throw new RuntimeException("MCP 服务器不存在");
        }

        // 解密 headers
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            server.setHeaders(AESUtil.decrypt(server.getHeaders(), cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8));
        }

        McpConnection conn = McpConnection.create(server, cfg.getMcp().getTool().getTimeoutSeconds());
        connections.put(key, conn);
        log.info("MCP 连接已建立: user={}, serverId={}, serverName={}, transport={}, 当前连接数={}",
                userName, serverId, server.getName(), server.getTransport(), connections.size());
        return conn;
    }

    public void removeConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.remove(key);
        if (conn != null) {
            conn.close();
            log.info("MCP 连接已关闭: user={}, serverId={}, 剩余连接数={}",
                    userName, serverId, connections.size());
        }
    }

    /** 删除服务器时关闭所有关联连接（共享服务器可能被多人使用） */
    public void removeAllConnectionsForServer(String serverId) {
        List<String> toRemove = new java.util.ArrayList<>();
        for (String key : connections.keySet()) {
            if (key.endsWith(":" + serverId)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            McpConnection conn = connections.remove(key);
            if (conn != null) conn.close();
        }
        if (!toRemove.isEmpty()) {
            log.info("MCP 服务器删除，已关闭 {} 个关联连接: serverId={}, 剩余连接数={}",
                    toRemove.size(), serverId, connections.size());
        }
    }

    public List<McpToolEntity> getAllEnabledTools(String userName) {
        List<McpToolEntity> tools = new java.util.ArrayList<>(mcpDao.queryEnabledToolsByUserName(userName));
        tools.addAll(mcpDao.queryEnabledToolsBySharedServers());
        return tools;
    }

    public String getServerOwner(String serverId) {
        McpServerEntity server = mcpDao.queryServerById(serverId);
        return server != null ? server.getUserName() : null;
    }

    private void evictLru() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, McpConnection> e : connections.entrySet()) {
            if (e.getValue().getLastUsedAt() < oldestTime) {
                oldestTime = e.getValue().getLastUsedAt();
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            McpConnection conn = connections.remove(oldest);
            if (conn != null) conn.close();
            log.info("LRU 淘汰 MCP 连接: {}, 当前连接数={}", oldest, connections.size());
        }
    }

    @Scheduled(fixedRateString = "${zephyr.mcp.connection.cleanup-interval-millis:300000}")
    public void cleanupIdle() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            long idleDuration = now - entry.getValue().getLastUsedAt();
            if (idleDuration > cfg.getMcp().getConnection().getIdleTimeoutMillis()) {
                entry.getValue().close();
                log.info("空闲 MCP 连接已回收: userServer={}, 空闲时间={}ms, 剩余连接数={}",
                        entry.getKey(), idleDuration, connections.size() - 1);
                return true;
            }
            return false;
        });
    }
}
