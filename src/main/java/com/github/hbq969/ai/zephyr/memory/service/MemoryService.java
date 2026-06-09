package com.github.hbq969.ai.zephyr.memory.service;

import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import java.util.List;
import java.util.Map;

public interface MemoryService {
    List<MemoryVO> list(String type, String userName);

    MemoryVO detail(String name, String userName);

    void create(Map<String, String> body, String userName);

    void update(Map<String, String> body, String userName);

    void delete(String namesStr, String userName);
}
