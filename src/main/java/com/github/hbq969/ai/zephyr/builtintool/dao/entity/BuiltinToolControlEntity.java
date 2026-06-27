package com.github.hbq969.ai.zephyr.builtintool.dao.entity;

import lombok.Data;

@Data
public class BuiltinToolControlEntity {
    private String toolName;
    private String description;
    private Integer requireAdmin;
    private Long createdAt;
    private Long updatedAt;
}
