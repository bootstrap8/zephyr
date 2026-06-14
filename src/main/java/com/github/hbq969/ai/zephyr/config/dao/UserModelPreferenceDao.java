package com.github.hbq969.ai.zephyr.config.dao;

import com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@DS
public interface UserModelPreferenceDao {
    void createUserModelPrefsTable();
    void upsert(UserModelPreferenceEntity entity);
    UserModelPreferenceEntity queryByUserAndType(@Param("userName") String userName, @Param("modelType") String modelType);
    void deleteByModelId(@Param("modelId") String modelId);
}
