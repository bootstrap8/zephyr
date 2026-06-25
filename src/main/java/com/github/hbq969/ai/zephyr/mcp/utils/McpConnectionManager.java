package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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

    /** 启动时保存的待重连服务器列表（reset 前查询，供 McpServiceImpl.reconnectOnStartup() 使用） */
    private volatile List<McpServerEntity> startupReconnectList;

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @PostConstruct
    void cleanupOrphanProcesses() {
        // 1. 记录重启前处于 connected 状态的服务器
        try {
            startupReconnectList = mcpDao.queryConnectedServers();
            log.info("启动时发现 {} 个 connected 状态的 MCP 服务器，将尝试重连", startupReconnectList.size());
        } catch (Exception e) {
            log.warn("查询 connected 服务器列表失败", e);
            startupReconnectList = List.of();
        }

        // 2. 清理孤儿进程
        try {
            Files.createDirectories(PIDS_DIR);
        } catch (Exception e) {
            log.warn("创建 MCP PID 目录失败: {}", PIDS_DIR, e);
        }
        File[] files = PIDS_DIR.toFile().listFiles((d, n) -> n.endsWith(".pid"));
        if (files == null || files.length == 0) {
            log.info("无孤儿 MCP 进程需要清理");
        } else {
            for (File f : files) {
                try {
                    long pid = Long.parseLong(Files.readString(f.toPath()).trim());
                    ProcessHandle.of(pid).ifPresent(ph -> {
                        List<ProcessHandle> children = ph.descendants().toList();
                        log.info("清理孤儿 MCP 进程树: parentPid={}, childrenPids={}, file={}",
                                pid, children.stream().map(c -> String.valueOf(c.pid())).toList(), f.getName());
                        children.forEach(ProcessHandle::destroyForcibly);
                        ph.destroyForcibly();
                    });
                    Files.deleteIfExists(f.toPath());
                } catch (Exception e) {
                    log.warn("清理孤儿进程失败: {}", f.getName(), e);
                }
            }
        }

        // 3. 重置所有 DB 状态（表可能尚未创建，忽略异常）
        try {
            mcpDao.resetAllServerStatus("disconnected");
            log.info("MCP 服务器状态已重置: 所有服务器设为 disconnected");
        } catch (Exception e) {
            log.warn("重置 MCP 服务器状态失败（表可能尚未创建）", e);
        }
    }

    /** 获取启动时需要重连的服务器列表 */
    public List<McpServerEntity> getStartupReconnectList() {
        return startupReconnectList != null ? startupReconnectList : List.of();
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
            throw new RuntimeException("MCP 连接数已达上限: " + cfg.getMcp().getConnection().getMaxConnections());
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

    @PreDestroy
    public void destroy() {
        int count = connections.size();
        for (Map.Entry<String, McpConnection> entry : connections.entrySet()) {
            entry.getValue().close();
            int idx = entry.getKey().indexOf(':');
            if (idx > 0) {
                try {
                    mcpDao.updateServerStatus(entry.getKey().substring(idx + 1), "disconnected",
                            entry.getKey().substring(0, idx));
                } catch (Exception e) {
                    log.warn("更新 MCP 服务器状态失败: key={}", entry.getKey(), e);
                }
            }
        }
        connections.clear();
        log.info("MCP 连接管理器已销毁，已关闭 {} 个连接", count);
    }
}
