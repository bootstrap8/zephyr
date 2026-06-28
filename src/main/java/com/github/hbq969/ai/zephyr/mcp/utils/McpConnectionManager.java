package com.github.hbq969.ai.zephyr.mcp.utils;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.github.hbq969.code.common.initial.event.ScriptInitialDoneEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@Order(1)
public class McpConnectionManager implements ApplicationListener<ScriptInitialDoneEvent> {



    private static final Path PIDS_DIR = Paths.get(System.getProperty("user.home"), MCP_PIDS_DIR);

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @Override
    public void onApplicationEvent(ScriptInitialDoneEvent event) {
        if (!initialized.compareAndSet(false, true)) {
            log.info("MCP 启动清理已跳过（已执行过一次），避免重复初始化");
            return;
        }
        cleanupOrphanProcesses();
    }

    void cleanupOrphanProcesses() {
        log.info("开始启动清理 MCP 服务器...");

        // 1. 清理孤儿进程（PID 文件驱动，重连逻辑已移至 McpServiceImpl.reconnectOnStartup()）
        try {
            Files.createDirectories(PIDS_DIR);
        } catch (Exception e) {
            log.warn("创建 MCP PID 目录失败: {}", PIDS_DIR, e);
        }
        File[] files = PIDS_DIR.toFile().listFiles((d, n) -> n.endsWith(EXT_PID));
        if (files == null || files.length == 0) {
            log.info("无孤儿 MCP 进程（无 PID 文件）");
        } else {
            // 逐文件处理：每行一个 PID（第一行为父进程，后续为启动时快照的子进程）
            int killed = 0, survived = 0;
            for (File f : files) {
                List<Long> pids;
                try {
                    pids = Files.readAllLines(f.toPath()).stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(Long::parseLong)
                            .toList();
                } catch (Exception e) {
                    log.warn("读取 PID 文件失败: {}", f.getName(), e);
                    try { Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
                    continue;
                }

                if (pids.isEmpty()) {
                    try { Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
                    continue;
                }

                // 先杀子进程（逆序，因为文件第一行是父进程，后面是子进程）
                for (int i = pids.size() - 1; i >= 0; i--) {
                    long pid = pids.get(i);
                    ProcessHandle.of(pid).ifPresent(ph -> {
                        // 再次获取最新 descendants（可能有孙子进程在快照后才启动）
                        for (ProcessHandle child : ph.descendants().toList()) {
                            child.destroyForcibly();
                        }
                        boolean ok = ph.destroyForcibly();
                        if (!ok) {
                            log.warn("MCP 进程销毁返回 false: pid={}, file={}", pid, f.getName());
                        }
                    });
                }

                // 确认所有 PID 是否都已退出
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}

                boolean allDead = true;
                for (long pid : pids) {
                    if (ProcessHandle.of(pid).isPresent()) {
                        allDead = false;
                        survived++;
                        log.warn("MCP 孤儿进程残留: pid={}, file={}", pid, f.getName());
                    }
                }

                if (allDead) {
                    try { Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
                    killed++;
                    log.info("MCP 孤儿进程已清理: file={}, pids={}, 共 {} 个进程", f.getName(), pids, pids.size());
                }
                // 如果有进程未死，保留 PID 文件供下次重启再次尝试
            }
            log.info("孤儿 MCP 清理完成: 已清理 {} 组, 残留 {} 个进程", killed, survived);
        }

        // 3. 重置所有 DB 状态（表可能尚未创建，忽略异常）
        try {
            mcpDao.resetAllServerStatus("disconnected");
            log.info("MCP 服务器状态已重置: 所有服务器设为 disconnected");
        } catch (Exception e) {
            log.warn("重置 MCP 服务器状态失败（表可能尚未创建）", e);
        }

        log.info("启动清理 MCP 服务器完成");
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
        log.info("开始销毁 MCP 连接管理器, 当前连接数={}", connections.size());
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
