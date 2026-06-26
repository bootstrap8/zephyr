package com.github.hbq969.ai.zephyr.workspace.dao;

import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface WorkspaceDao {

    void createWorkspacesTable();

    List<WorkspaceEntity> queryByUserName(@Param("userName") String userName);
    void insert(WorkspaceEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    WorkspaceEntity queryById(@Param("id") String id);
    WorkspaceEntity queryByPath(@Param("path") String path, @Param("userName") String userName);
    void updateIsSystem(@Param("id") String id, @Param("isSystem") Integer isSystem);
}
