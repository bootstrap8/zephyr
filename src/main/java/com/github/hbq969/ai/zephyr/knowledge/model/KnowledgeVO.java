package com.github.hbq969.ai.zephyr.knowledge.model;

import lombok.Data;

@Data
public class KnowledgeVO {
    private String id;
    private String name;
    private String description;
    private String embedModelId;
    private String embedModelName;
    private int docCount;
    private Long createdAt;
    private Long updatedAt;
}
