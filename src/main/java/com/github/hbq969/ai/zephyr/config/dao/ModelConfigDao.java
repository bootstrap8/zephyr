package com.github.hbq969.ai.zephyr.config.dao;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
@DS
public interface ModelConfigDao {
    void createModelConfigsTable();
    List<ModelConfigEntity> queryByUserName(@Param("userName") String userName);
    void insert(ModelConfigEntity entity);
    void update(ModelConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    ModelConfigEntity queryById(@Param("id") String id);
    void clearDefault(@Param("userName") String userName);
    void setDefault(@Param("id") String id, @Param("userName") String userName);

    List<ModelConfigEntity> queryByType(@Param("userName") String userName, @Param("modelType") String modelType);

    ModelConfigEntity queryDefaultByType(@Param("modelType") String modelType);

    void clearDefaultByType(@Param("modelType") String modelType);

    List<ModelConfigEntity> queryShared();

    void toggleScope(@Param("id") String id, @Param("scope") String scope, @Param("updatedAt") Long updatedAt);

    @Update("update zephyr_model_configs set max_context_tokens = #{maxTokens}, updated_at = #{updatedAt} where id = #{id} and user_name = #{userName}")
    void updateMaxContextTokens(@Param("id") String id, @Param("maxTokens") Long maxTokens, @Param("updatedAt") Long updatedAt, @Param("userName") String userName);
}
