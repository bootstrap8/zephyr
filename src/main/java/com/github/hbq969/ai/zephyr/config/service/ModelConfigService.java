package com.github.hbq969.ai.zephyr.config.service;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import java.util.List;
import java.util.Map;

public interface ModelConfigService {
    List<ModelConfigEntity> list(String userName);
    ModelConfigEntity create(Map<String, String> body, String userName);
    void update(Map<String, String> body, String userName);
    void delete(String id, String userName);
    void setDefault(String id, String userName);
    Long detectContext(String id, String userName);
}