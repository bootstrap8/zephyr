package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class LlmResult {
    private String content;
    private String thinking;
    private List<ToolCall> toolCalls;
    private Map<String, Integer> usage;

    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
