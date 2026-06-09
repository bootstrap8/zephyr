package com.github.hbq969.ai.zephyr.memory.model;

import lombok.Data;

@Data
public class MemoryVO {
    private String name;
    private String type;
    private String description;
    private String content;
    private long createdAt;
    private long updatedAt;
}
