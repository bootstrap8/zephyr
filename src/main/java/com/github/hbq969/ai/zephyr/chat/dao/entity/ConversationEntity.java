package com.github.hbq969.ai.zephyr.chat.dao.entity;

import lombok.Data;

@Data
public class ConversationEntity {
    private String id;
    private String userName;
    private String title;
    private String workspaceId;
    private Long createdAt;
    private Long updatedAt;
}
