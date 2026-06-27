package com.github.hbq969.ai.zephyr.mcp.service.impl;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnection;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.github.hbq969.code.common.initial.event.ScriptInitialDoneEvent;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Order(2)
public class McpServiceImpl implements McpService, ApplicationListener<ScriptInitialDoneEvent> {

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
        entity.setId(UUID.fastUUID().toString(true).substring(0, SHORT_ID_LENGTH));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", TRANSPORT_STDIO));
        entity.setCommand(body.getOrDefault("command", ""));
        entity.setArgs(body.getOrDefault("args", ""));
        entity.setEnvVars(body.getOrDefault("envVars", ""));
        entity.setUrl(body.getOrDefault("url", ""));
        String headers = body.getOrDefault("headers", "");
        entity.setHeaders(headers.isEmpty() ? "" : encryptHeaders(headers));
        entity.setStatus(STATUS_DISCONNECTED);
        entity.setScope(scope);
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mcpDao.insertServer(entity);
        log.info("MCP 服务器已创建: name={}, transport={}, scope={}, user={}",
                name, entity.getTransport(), scope, userName);
        entity.setHeaders(headers.isEmpty() ? "" : maskHeaders(entity.getHeaders()));
        return entity;
    }

    @Override
    @Transactional
    public void updateServer(Map<String, String> body, String userName) {
        String id = body.get("id");
        McpServerEntity oldServer = mcpDao.queryServerById(id);

        McpServerEntity entity = new McpServerEntity();
        entity.setId(id);
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setTransport(body.getOrDefault("transport", TRANSPORT_STDIO));
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
        log.info("MCP 服务器配置已更新: id={}, name={}, user={}", id, body.get("name"), userName);

        // 如果启动配置有变化，关闭所有旧连接，下次工具调用时懒重建
        if (oldServer != null && hasConnectionConfigChanged(oldServer, body)) {
            connectionManager.removeAllConnectionsForServer(id);
            log.info("MCP 更新导致连接参数变化，已关闭旧连接: id={}", id);
        }
    }

    private boolean hasConnectionConfigChanged(McpServerEntity old, Map<String, String> body) {
        String newCmd = body.getOrDefault("command", "");
        String newArgs = body.getOrDefault("args", "");
        String newEnv = body.getOrDefault("envVars", "");
        String newUrl = body.getOrDefault("url", "");
        String newHeaders = body.getOrDefault("headers", "");
        return !old.getCommand().equals(newCmd)
                || !nullToEmpty(old.getArgs()).equals(newArgs)
                || !nullToEmpty(old.getEnvVars()).equals(newEnv)
                || !nullToEmpty(old.getUrl()).equals(newUrl)
                || !newHeaders.isEmpty(); // headers 重新提交了就认为可能有变化
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    @Transactional
    public void deleteServer(String id, String userName) {
        McpServerEntity server = mcpDao.queryServerById(id);
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        log.info("MCP 服务器已删除: id={}, name={}, user={}", id,
                server != null ? server.getName() : id, userName);
        connectionManager.removeAllConnectionsForServer(id);
        mcpDao.deleteToolsByServerId(id, server != null ? server.getUserName() : userName);
        mcpDao.deleteServer(id, userName);
    }

    @Override
    public void connect(String id, String userName) {
        connect0(id, userName, true);
    }

    private void connect0(String id, String userName, boolean checkPermission) {
        McpServerEntity server = mcpDao.queryServerById(id);
        if (server == null) {
            log.warn("MCP 连接失败，服务器不存在: id={}", id);
            return;
        }

        if (checkPermission && SCOPE_SHARED.equals(server.getScope())) {
            checkSharedManage();
        }

        log.info("MCP 开始连接: name={}, transport={}, command={}, user={}",
                server.getName(), server.getTransport(), server.getCommand(), userName);

        // 先关闭已有的连接
        connectionManager.removeAllConnectionsForServer(id);

        String encryptedHeaders = server.getHeaders();
        if (encryptedHeaders != null && !encryptedHeaders.isEmpty()) {
            server.setHeaders(AESUtil.decrypt(encryptedHeaders, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8));
        }

        McpConnection conn = null;
        try {
            // 创建持久连接（启动 STDIO 进程 + MCP 握手）
            conn = connectionManager.getConnection(userName, id);

            // 从持久连接发现工具
            List<McpToolEntity> discovered = conn.listTools();

            mcpDao.deleteToolsByServerId(id, server.getUserName());

            // 检查工具名冲突
            for (McpToolEntity t : discovered) {
                McpToolEntity dup = mcpDao.queryToolByNameAndUser(t.getToolName(), server.getUserName());
                if (dup != null) {
                    log.warn("MCP 连接失败，工具名冲突: server={}, tool={}", server.getName(), t.getToolName());
                    throw new RuntimeException("工具名 \"" + t.getToolName() + "\" 已存在，连接失败");
                }
            }

            if (discovered.isEmpty()) {
                log.warn("MCP 连接失败，未发现工具: server={}", server.getName());
                mcpDao.updateServerStatus(id, STATUS_ERROR, userName);
                connectionManager.removeConnection(userName, id);
                return;
            }

            long now = System.currentTimeMillis() / 1000;
            for (McpToolEntity t : discovered) {
                t.setId(UUID.fastUUID().toString(true).substring(0, SHORT_ID_LENGTH));
                t.setUserName(server.getUserName());
                t.setServerId(id);
                t.setCreatedAt(now);
                mcpDao.insertTool(t);
            }

            mcpDao.updateServerStatus(id, STATUS_CONNECTED, userName);
            log.info("MCP 连接成功: name={}, 发现 {} 个工具, user={}",
                    server.getName(), discovered.size(), userName);
        } catch (RuntimeException e) {
            log.warn("MCP 连接失败: name={}, error={}", server.getName(), e.getMessage());
            mcpDao.updateServerStatus(id, STATUS_ERROR, userName);
            if (conn != null) {
                connectionManager.removeConnection(userName, id);
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public void disconnect(String id, String userName) {
        McpServerEntity server = mcpDao.queryServerById(id);
        log.info("MCP 断开连接: id={}, name={}, user={}", id,
                server != null ? server.getName() : id, userName);
        if (server != null && SCOPE_SHARED.equals(server.getScope())) {
            connectionManager.removeAllConnectionsForServer(id);
        } else {
            connectionManager.removeConnection(userName, id);
        }
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
        entity.setId(UUID.fastUUID().toString(true).substring(0, SHORT_ID_LENGTH));
        entity.setUserName(toolUser);
        entity.setServerId(serverId);
        entity.setToolName(body.get("toolName"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEnabled(1);
        entity.setSource(SOURCE_MANUAL);
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        mcpDao.insertTool(entity);
        log.info("MCP 工具已手动添加: toolName={}, serverId={}, user={}",
                body.get("toolName"), serverId, toolUser);
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
        log.info("MCP 工具已删除: toolName={}, serverId={}, user={}",
                tool.getToolName(), tool.getServerId(), userName);
        mcpDao.deleteTool(id, tool.getUserName());
    }

    @Override
    @Transactional
    public void toggleTool(String id, Integer enabled, String userName) {
        McpToolEntity tool = mcpDao.queryToolById(id);
        if (tool == null) return;
        McpServerEntity server = mcpDao.queryServerById(tool.getServerId());
        if (server != null && SCOPE_SHARED.equals(server.getScope())) checkSharedManage();
        log.info("MCP 工具{}: toolName={}, serverId={}, user={}",
                enabled == 1 ? "已启用" : "已禁用", tool.getToolName(), tool.getServerId(), userName);
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
        McpServerEntity server = mcpDao.queryServerById(id);
        log.info("MCP 范围已变更: id={}, name={}, scope={}",
                id, server != null ? server.getName() : id, scope);
        // 取消共享时，关闭其他用户持有的连接
        if (server != null && SCOPE_SHARED.equals(server.getScope()) && SCOPE_USER.equals(scope)) {
            connectionManager.removeAllConnectionsForServer(id);
            log.info("MCP 取消共享，已关闭所有关联连接: id={}", id);
        }
        mcpDao.updateServerScope(id, scope);
    }

    private String encryptHeaders(String plain) {
        return AESUtil.encrypt(plain, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8);
    }

    @Override
    public void onApplicationEvent(ScriptInitialDoneEvent event) {
        reconnectOnStartup();
    }

    @Override
    public void reconnectOnStartup() {
        List<McpServerEntity> servers = connectionManager.getStartupReconnectList();
        if (servers.isEmpty()) {
            log.info("无需要重连的 MCP 服务器");
            return;
        }
        log.info("开始重连 {} 个 MCP 服务器", servers.size());
        int ok = 0;
        for (McpServerEntity s : servers) {
            try {
                connect0(s.getId(), s.getUserName(), false);
                ok++;
                log.info("MCP 启动重连成功: server={}, user={}", s.getName(), s.getUserName());
            } catch (Exception e) {
                log.warn("MCP 启动重连失败: server={}, user={}, error={}",
                        s.getName(), s.getUserName(), e.getMessage());
            }
        }
        log.info("MCP 启动重连完成: 成功 {}/{}", ok, servers.size());
    }

    private String maskHeaders(String encrypted) {
        if (encrypted.length() <= MASK_THRESHOLD_LENGTH) return MASK_STRING;
        return encrypted.substring(0, MASK_PREFIX_LENGTH) + MASK_STRING + encrypted.substring(encrypted.length() - MASK_SUFFIX_LENGTH);
    }
}
