package com.github.hbq969.ai.zephyr.skill.model;

import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import lombok.Data;

@Data
public class SkillVO {
    private String id;
    private String skillName;
    private String displayName;
    private String description;
    private String scope;
    private String source;
    private String sourceUrl;
    private String version;
    private boolean enabled;
    private String installPath;
    private Long createdAt;
    private Long updatedAt;
    private String platform;
    private String platformPath;

    public static SkillVO fromEntity(SkillConfigEntity e) {
        SkillVO vo = new SkillVO();
        vo.setId(e.getId());
        vo.setSkillName(e.getSkillName());
        vo.setDisplayName(e.getDisplayName() != null ? e.getDisplayName() : e.getSkillName());
        vo.setDescription(e.getDescription());
        vo.setScope(e.getScope());
        vo.setSource(e.getSource());
        vo.setSourceUrl(e.getSourceUrl());
        vo.setVersion(e.getVersion());
        vo.setEnabled(e.getEnabled() != null && e.getEnabled() == 1);
        vo.setInstallPath(e.getInstallPath());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
