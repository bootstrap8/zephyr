package com.github.hbq969.ai.zephyr.mcp.dao.entity;

import lombok.Data;

@Data
public class McpToolEntity {
    private String id;
    private String userName;
    private String serverId;
    private String toolName;
    private String description;
    private String parametersJson;
    private Integer enabled;
    private String source;
    private Long createdAt;
}
