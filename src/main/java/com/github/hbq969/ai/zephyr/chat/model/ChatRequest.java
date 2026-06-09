package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String conversationId;
    private String message;
}
