package com.github.hbq969.ai.zephyr.mcp.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.ai.zephyr.mcp.utils.McpClient;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class McpServiceImpl implements McpService {

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    @Resource
    private McpDao mcpDao;

    @Resource
    private com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager connectionManager;

    @Override
    public List<McpServerEntity> listServers(String userName) {
        List<McpServerEntity> list = mcpDao.queryServersByUserName(userName);
        for (McpServerEntity e : list) {
            String h = e.getHeaders();
            if (h != null && !h.isEmpty()) {
                e.setHeaders(maskHeaders(h));
            }
        }
        return list;
    }

    @Override
    @Transactional
    public McpServerEntity createServer(Map<String, String> body, String userName) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", "stdio"));
        entity.setCommand(body.getOrDefault("command", ""));
        entity.setArgs(body.getOrDefault("args", ""));
        entity.setEnvVars(body.getOrDefault("envVars", ""));
        entity.setUrl(body.getOrDefault("url", ""));
        String headers = body.getOrDefault("headers", "");
        entity.setHeaders(headers.isEmpty() ? "" : encryptHeaders(headers));
        entity.setStatus("disconnected");
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mcpDao.insertServer(entity);
        entity.setHeaders(headers.isEmpty() ? "" : maskHeaders(entity.getHeaders()));
        return entity;
    }

    @Override
    @Transactional
    public void updateServer(Map<String, String> body, String userName) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", "stdio"));
        entity.setCommand(body.getOrDefault("command", ""));
        entity.setArgs(body.getOrDefault("args", ""));
        entity.setEnvVars(body.getOrDefault("envVars", ""));
        entity.setUrl(body.getOrDefault("url", ""));
        String headers = body.getOrDefault("headers", "");
        if (!headers.isEmpty()) {
            entity.setHeaders(encryptHeaders(headers));
        }
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        mcpDao.updateServer(entity);
    }

    @Override
    @Transactional
    public void deleteServer(String id, String userName) {
        mcpDao.deleteToolsByServerId(id, userName);
        mcpDao.deleteServer(id, userName);
    }

    @Override
    @Transactional
    public void connect(String id, String userName) {
        McpServerEntity server = mcpDao.queryServerById(id);
        if (server == null) return;

        // 解密 headers 供 MCP 客户端请求使用
        String encryptedHeaders = server.getHeaders();
        if (encryptedHeaders != null && !encryptedHeaders.isEmpty()) {
            server.setHeaders(AESUtil.decrypt(encryptedHeaders, aesKey, aesIv, StandardCharsets.UTF_8));
        }

        // 拉取工具列表
        List<McpToolEntity> discovered = McpClient.discoverTools(server);

        // 清除旧的自动发现工具，保留手动添加的
        mcpDao.deleteToolsByServerId(id, userName);

        if (discovered.isEmpty()) {
            mcpDao.updateServerStatus(id, "error", userName);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        for (McpToolEntity t : discovered) {
            t.setId(UUID.fastUUID().toString(true).substring(0, 12));
            t.setUserName(userName);
            t.setServerId(id);
            t.setCreatedAt(now);
            mcpDao.insertTool(t);
        }

        mcpDao.updateServerStatus(id, "connected", userName);
    }

    @Override
    @Transactional
    public void disconnect(String id, String userName) {
        connectionManager.removeConnection(userName, id);
        mcpDao.updateServerStatus(id, "disconnected", userName);
    }

    @Override
    public List<McpToolEntity> listTools(String serverId, String userName) {
        return mcpDao.queryToolsByServerId(serverId, userName);
    }

    @Override
    @Transactional
    public McpToolEntity createTool(Map<String, String> body, String userName) {
        McpToolEntity entity = new McpToolEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setServerId(body.get("serverId"));
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEnabled(1);
        entity.setSource("manual");
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        mcpDao.insertTool(entity);
        return entity;
    }

    @Override
    @Transactional
    public void updateTool(Map<String, String> body, String userName) {
        McpToolEntity entity = new McpToolEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        mcpDao.updateTool(entity);
    }

    @Override
    @Transactional
    public void deleteTool(String id, String userName) {
        mcpDao.deleteTool(id, userName);
    }

    @Override
    @Transactional
    public void toggleTool(String id, Integer enabled, String userName) {
        mcpDao.toggleTool(id, enabled, userName);
    }

    @Override
    public int countEnabledTools(String userName) {
        return mcpDao.countEnabledTools(userName);
    }

    private String encryptHeaders(String plain) {
        return AESUtil.encrypt(plain, aesKey, aesIv, StandardCharsets.UTF_8);
    }

    private String maskHeaders(String encrypted) {
        if (encrypted.length() <= 8) return "****";
        return encrypted.substring(0, 4) + "****" + encrypted.substring(encrypted.length() - 4);
    }
}
