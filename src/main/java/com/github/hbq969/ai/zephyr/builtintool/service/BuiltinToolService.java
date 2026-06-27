package com.github.hbq969.ai.zephyr.builtintool.service;

import com.github.hbq969.ai.zephyr.builtintool.dao.entity.BuiltinToolControlEntity;

import java.util.List;

public interface BuiltinToolService {

    /** 检查指定工具是否需要 admin 权限。admin 用户永远返回 false。 */
    boolean requiresAdmin(String userName, String toolName);

    List<BuiltinToolControlEntity> list();

    void toggle(String toolName, int requireAdmin);

    /** 启动/配置变更时刷新内存缓存 */
    void refreshCache();
}
