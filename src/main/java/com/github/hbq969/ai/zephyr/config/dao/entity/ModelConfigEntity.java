package com.github.hbq969.ai.zephyr.config.dao.entity;

import lombok.Data;

@Data
public class ModelConfigEntity {
    private String id;
    private String userName;
    private String name;
    private String baseUrl;
    private String apiKeyEncrypted;
    private Integer isDefault;
    private Long createdAt;
    private Long updatedAt;
    private Long maxContextTokens;
    private String params;
    private String modelType;
    private String scope = "user";
    private Integer dimensions;
}
