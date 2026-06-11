package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    private String conversationId;
    private String workspaceId;
    private String message;
    private List<String> filePaths;
}
