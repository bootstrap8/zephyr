package com.github.hbq969.ai.zephyr.knowledge.dao.entity;

import lombok.Data;

@Data
public class KnowledgeBaseEntity {
    private String id;
    private String userName;
    private String name;
    private String description;
    private String embedModelId;
    private String scope = "user";
    private Boolean graphEnabled;
    private Long createdAt;
    private Long updatedAt;
}
