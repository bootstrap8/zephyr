package com.github.hbq969.ai.zephyr.builtintool.dao;

import com.github.hbq969.ai.zephyr.builtintool.dao.entity.BuiltinToolControlEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface BuiltinToolDao {

    void createBuiltinToolControlsTable();

    List<BuiltinToolControlEntity> queryAll();

    void updateRequireAdmin(@Param("toolName") String toolName, @Param("requireAdmin") int requireAdmin, @Param("updatedAt") long updatedAt);

    void insert(@Param("entity") BuiltinToolControlEntity entity);
}
