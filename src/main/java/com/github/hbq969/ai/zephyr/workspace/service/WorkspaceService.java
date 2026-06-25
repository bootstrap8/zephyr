package com.github.hbq969.ai.zephyr.workspace.service;

import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;

import java.util.List;
import java.util.Map;

public interface WorkspaceService {
    List<WorkspaceEntity> list(String userName);
    WorkspaceEntity create(Map<String, String> body, String userName);
    void delete(String id, String userName);
    List<Map<String, Object>> browse(String parent);
    String mkdir(String parent, String name);
}
