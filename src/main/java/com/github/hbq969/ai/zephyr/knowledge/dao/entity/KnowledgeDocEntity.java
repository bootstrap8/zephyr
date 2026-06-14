package com.github.hbq969.ai.zephyr.knowledge.dao.entity;

import lombok.Data;

@Data
public class KnowledgeDocEntity {
    private String id;
    private String kbId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String content;
    private String sourceType;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private String graphStatus;
    private Long createdAt;
    private Long updatedAt;
}
