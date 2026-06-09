package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolDef {
    private String type;
    private FunctionDef function;

    @Data
    @Builder
    public static class FunctionDef {
        private String name;
        private String description;
        private Object parameters;
    }
}
