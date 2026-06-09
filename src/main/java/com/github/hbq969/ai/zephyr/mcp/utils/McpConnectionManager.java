package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpConnectionManager {

    private static final int MAX_CONNECTIONS = 100;
    private static final long IDLE_TIMEOUT_MS = 15 * 60 * 1000L; // 15 分钟

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    @Resource
    private McpDao mcpDao;

    public McpConnection getConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.get(key);
        if (conn != null) {
            return conn; // touch 在 callTool 中更新
        }
        return createConnection(key, userName, serverId);
    }

    private synchronized McpConnection createConnection(String key, String userName, String serverId) {
        McpConnection existing = connections.get(key);
        if (existing != null) return existing;

        if (connections.size() >= MAX_CONNECTIONS) {
            evictLru();
        }

        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server == null || !server.getUserName().equals(userName)) {
            throw new RuntimeException("MCP 服务器不存在");
        }

        // 解密 headers
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            server.setHeaders(AESUtil.decrypt(server.getHeaders(), aesKey, aesIv, StandardCharsets.UTF_8));
        }

        McpConnection conn = McpConnection.create(server);
        connections.put(key, conn);
        log.info("MCP 连接已建立: {} (当前 {} 个连接)", key, connections.size());
        return conn;
    }

    public void removeConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.remove(key);
        if (conn != null) {
            conn.close();
            log.info("MCP 连接已关闭: {}", key);
        }
    }

    public List<McpToolEntity> getAllEnabledTools(String userName) {
        return mcpDao.queryEnabledToolsByUserName(userName);
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
            log.info("LRU 淘汰连接: {}", oldest);
        }
    }

    @Scheduled(fixedRate = 300000) // 每 5 分钟
    public void cleanupIdle() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getLastUsedAt() > IDLE_TIMEOUT_MS) {
                entry.getValue().close();
                log.info("空闲连接回收: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
