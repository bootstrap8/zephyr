package com.github.hbq969.ai.zephyr.mcp.service;

import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;

import java.util.List;
import java.util.Map;

public interface McpService {
    List<McpServerEntity> listServers(String userName);
    McpServerEntity createServer(Map<String, String> body, String userName);
    void updateServer(Map<String, String> body, String userName);
    void deleteServer(String id, String userName);
    void connect(String id, String userName);
    void disconnect(String id, String userName);

    List<McpToolEntity> listTools(String serverId, String userName);
    McpToolEntity createTool(Map<String, String> body, String userName);
    void updateTool(Map<String, String> body, String userName);
    void deleteTool(String id, String userName);
    void toggleTool(String id, Integer enabled, String userName);

    int countEnabledTools(String userName);
    void toggleServerScope(String id, String scope);

    /** Zephyr 启动后重连之前处于 connected 状态的 MCP 服务器 */
    void reconnectOnStartup();
}
