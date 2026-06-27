package com.github.hbq969.ai.zephyr.mcp.dao;

import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface McpDao {

    void createMcpServersTable();
    void createMcpToolsTable();

    List<McpServerEntity> queryServersByUserName(@Param("userName") String userName);
    void insertServer(McpServerEntity entity);
    void updateServer(McpServerEntity entity);
    void deleteServer(@Param("id") String id, @Param("userName") String userName);
    McpServerEntity queryServerById(@Param("id") String id);
    void updateServerStatus(@Param("id") String id, @Param("status") String status, @Param("userName") String userName);

    void resetAllServerStatus(@Param("status") String status);

    List<McpServerEntity> queryConnectedServers();

    McpToolEntity queryToolById(@Param("id") String id);
    List<McpToolEntity> queryToolsByServerId(@Param("serverId") String serverId, @Param("userName") String userName);
    void insertTool(McpToolEntity entity);
    void updateTool(McpToolEntity entity);
    void deleteTool(@Param("id") String id, @Param("userName") String userName);
    void deleteToolsByServerId(@Param("serverId") String serverId, @Param("userName") String userName);
    void toggleTool(@Param("id") String id, @Param("enabled") Integer enabled, @Param("userName") String userName);

    int countEnabledTools(@Param("userName") String userName);
    List<McpToolEntity> queryEnabledToolsByUserName(@Param("userName") String userName);
    List<McpToolEntity> queryToolsByUserName(@Param("userName") String userName);

    List<McpServerEntity> querySharedServers();
    List<McpToolEntity> queryEnabledToolsBySharedServers();
    List<McpToolEntity> queryToolsBySharedServers();
    void updateServerScope(@Param("id") String id, @Param("scope") String scope);

    McpServerEntity queryByNameAndScope(@Param("name") String name, @Param("scope") String scope);

    McpToolEntity queryToolByName(@Param("toolName") String toolName);

    McpToolEntity queryToolByNameAndUser(@Param("toolName") String toolName, @Param("userName") String userName);

    List<String> queryAllDistinctToolNames();
}
