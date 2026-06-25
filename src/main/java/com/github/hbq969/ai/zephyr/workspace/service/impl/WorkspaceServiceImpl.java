package com.github.hbq969.ai.zephyr.workspace.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.ai.zephyr.workspace.service.WorkspaceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {

    @Resource
    private WorkspaceDao workspaceDao;

    @Override
    public List<WorkspaceEntity> list(String userName) {
        return workspaceDao.queryByUserName(userName);
    }

    @Override
    @Transactional
    public WorkspaceEntity create(Map<String, String> body, String userName) {
        String name = body.get("name");
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            throw new RuntimeException("目录路径不能为空");
        }
        WorkspaceEntity existing = workspaceDao.queryByPath(path, userName);
        if (existing != null) {
            return existing;
        }
        if (name == null || name.isBlank()) {
            name = java.nio.file.Path.of(path).getFileName().toString();
        }
        long now = System.currentTimeMillis() / 1000;
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setName(name);
        entity.setPath(path);
        entity.setUserName(userName);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        workspaceDao.delete(id, userName);
    }

    @Override
    public List<Map<String, Object>> browse(String parent) {
        List<Map<String, Object>> result = new ArrayList<>();
        String root = parent != null && !parent.isBlank() ? parent : System.getProperty("user.home");
        File dir = new File(root);
        if (!dir.exists() || !dir.isDirectory()) return result;

        // 上级目录
        File parentFile = dir.getParentFile();
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("name", "..");
        up.put("path", parentFile != null ? parentFile.getAbsolutePath() : root);
        result.add(up);

        // 子目录
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : children) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath());
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public String mkdir(String parent, String name) {
        if (name == null || name.isBlank()) {
            throw new RuntimeException("目录名称不能为空");
        }
        if (name.contains("/") || name.contains("\0")) {
            throw new RuntimeException("目录名称包含非法字符");
        }
        java.nio.file.Path newDir = java.nio.file.Path.of(parent, name);
        try {
            java.nio.file.Files.createDirectory(newDir);
            return newDir.toAbsolutePath().toString();
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new RuntimeException("目录已存在: " + newDir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
    }
}
