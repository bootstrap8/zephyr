package com.github.hbq969.ai.zephyr.config.dao.entity;

import lombok.Data;

@Data
public class UserModelPreferenceEntity {
    private String id;
    private String userName;
    private String modelType;
    private String modelId;
    private Long createdAt;
    private Long updatedAt;
}
