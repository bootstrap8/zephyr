package com.github.hbq969.ai.zephyr.skill.dao.entity;

import lombok.Data;

@Data
public class SkillConfigEntity {
    private String id;
    private String userName;
    private String skillName;
    private String displayName;
    private String description;
    private String scope = "user";
    private String source;
    private String sourceUrl;
    private String version;
    private Integer enabled;
    private String installPath;
    private Long createdAt;
    private Long updatedAt;
}
