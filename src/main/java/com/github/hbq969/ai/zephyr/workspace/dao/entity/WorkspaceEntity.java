package com.github.hbq969.ai.zephyr.workspace.dao.entity;

import lombok.Data;

@Data
public class WorkspaceEntity {
    private String id;
    private String name;
    private String path;
    private String userName;
    private Integer isSystem; // 0=用户创建, 1=系统创建
    private Long createdAt;
    private Long updatedAt;
}
