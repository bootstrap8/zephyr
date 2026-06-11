package com.github.hbq969.ai.zephyr.chat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {
    private String id;
    private String title;
    private String workspaceId;
    private long updatedAt;
    private long createdAt;
    private int messageCount;
}
