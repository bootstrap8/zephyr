package com.github.hbq969.ai.zephyr.mcp.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.ai.zephyr.mcp.utils.McpClient;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class McpServiceImpl implements McpService {

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @Resource
    private McpDao mcpDao;

    @Resource
    private com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager connectionManager;

    private static final String SCOPE_SHARED = "shared";
    private static final String SCOPE_USER = "user";

    private boolean isAdmin() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null && ui.isAdmin();
    }

    private void checkSharedManage() {
        if (!isAdmin()) throw new RuntimeException("仅 admin 可管理共享 MCP 服务器");
    }

    @Override
    public List<McpServerEntity> listServers(String userName) {
        List<McpServerEntity> result = new ArrayList<>();
        result.addAll(mcpDao.queryServersByUserName(userName));
        result.addAll(mcpDao.querySharedServers());
        boolean admin = isAdmin();
        for (McpServerEntity e : result) {
            if (SCOPE_SHARED.equals(e.getScope())) {
                e.setCanManage(admin);
            } else if (admin && e.getUserName().equals(userName)) {
                e.setCanManage(true);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public McpServerEntity createServer(Map<String, String> body, String userName) {
        String scope = body.getOrDefault("scope", SCOPE_USER);
        if (SCOPE_SHARED.equals(scope)) checkSharedManage();

        String name = body.get("name");
        // 检查是否有同名共享 MCP 服务器
        McpServerEntity dup = mcpDao.queryByNameAndScope(name, SCOPE_SHARED);
        if (dup != null) {
            throw new RuntimeException("已存在同名共享 MCP 服务器 \"" + name + "\"，请使用其他名称");
        }

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
        entity.setScope(scope);
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
        McpServerEntity server = mcpDao.queryServerById(id);
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        mcpDao.deleteToolsByServerId(id, server != null ? server.getUserName() : userName);
        mcpDao.deleteServer(id, userName);
    }

    @Override
    @Transactional
    public void connect(String id, String userName) {
        McpServerEntity server = mcpDao.queryServerById(id);
        if (server == null) return;

        boolean isShared = SCOPE_SHARED.equals(server.getScope());
        if (isShared) checkSharedManage();

        String encryptedHeaders = server.getHeaders();
        if (encryptedHeaders != null && !encryptedHeaders.isEmpty()) {
            server.setHeaders(AESUtil.decrypt(encryptedHeaders, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8));
        }

        List<McpToolEntity> discovered = McpClient.discoverTools(server);
        mcpDao.deleteToolsByServerId(id, server.getUserName());

        if (discovered.isEmpty()) {
            mcpDao.updateServerStatus(id, "error", userName);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        for (McpToolEntity t : discovered) {
            t.setId(UUID.fastUUID().toString(true).substring(0, 12));
            t.setUserName(server.getUserName());
            t.setServerId(id);
            t.setCreatedAt(now);
            mcpDao.insertTool(t);
        }

        mcpDao.updateServerStatus(id, "connected", userName);
    }

    @Override
    @Transactional
    public void disconnect(String id, String userName) {
        McpServerEntity server = mcpDao.queryServerById(id);
        connectionManager.removeConnection(userName, id);
        mcpDao.updateServerStatus(id, "disconnected", userName);
    }

    @Override
    public List<McpToolEntity> listTools(String serverId, String userName) {
        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server != null && SCOPE_SHARED.equals(server.getScope())) {
            return mcpDao.queryToolsByServerId(serverId, server.getUserName());
        }
        return mcpDao.queryToolsByServerId(serverId, userName);
    }

    @Override
    @Transactional
    public McpToolEntity createTool(Map<String, String> body, String userName) {
        String serverId = body.get("serverId");
        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        String toolUser = server != null ? server.getUserName() : userName;

        McpToolEntity entity = new McpToolEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(toolUser);
        entity.setServerId(serverId);
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
        McpToolEntity tool = mcpDao.queryToolById(body.get("id"));
        if (tool == null) return;
        McpServerEntity server = mcpDao.queryServerById(tool.getServerId());
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        McpToolEntity entity = new McpToolEntity();
        entity.setId(body.get("id"));
        entity.setUserName(tool.getUserName());
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        mcpDao.updateTool(entity);
    }

    @Override
    @Transactional
    public void deleteTool(String id, String userName) {
        McpToolEntity tool = mcpDao.queryToolById(id);
        if (tool == null) return;
        McpServerEntity server = mcpDao.queryServerById(tool.getServerId());
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        mcpDao.deleteTool(id, tool.getUserName());
    }

    @Override
    @Transactional
    public void toggleTool(String id, Integer enabled, String userName) {
        McpToolEntity tool = mcpDao.queryToolById(id);
        if (tool == null) return;
        McpServerEntity server = mcpDao.queryServerById(tool.getServerId());
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        mcpDao.toggleTool(id, enabled, tool.getUserName());
    }

    @Override
    public int countEnabledTools(String userName) {
        int count = mcpDao.countEnabledTools(userName);
        count += mcpDao.queryEnabledToolsBySharedServers().size();
        return count;
    }

    @Override
    @Transactional
    public void toggleServerScope(String id, String scope) {
        checkSharedManage();
        mcpDao.updateServerScope(id, scope);
    }

    private String encryptHeaders(String plain) {
        return AESUtil.encrypt(plain, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8);
    }

    private String maskHeaders(String encrypted) {
        if (encrypted.length() <= 8) return "****";
        return encrypted.substring(0, 4) + "****" + encrypted.substring(encrypted.length() - 4);
    }
}
