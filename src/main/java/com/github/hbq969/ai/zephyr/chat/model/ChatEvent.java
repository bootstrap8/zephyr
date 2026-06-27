package com.github.hbq969.ai.zephyr.chat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {
    private String type;
    private String content;
    private String toolName;
    private Object toolInput;
    private String toolOutput;
    private String toolStatus;
    private Object usage;
    private String error;
}
