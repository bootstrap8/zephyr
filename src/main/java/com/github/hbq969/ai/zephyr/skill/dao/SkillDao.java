package com.github.hbq969.ai.zephyr.skill.dao;

import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface SkillDao {

    void createSkillConfigsTable();

    List<SkillConfigEntity> queryByUserName(@Param("userName") String userName);
    List<SkillConfigEntity> queryEnabledByUserName(@Param("userName") String userName);
    List<SkillConfigEntity> queryShared();
    SkillConfigEntity queryById(@Param("id") String id);
    SkillConfigEntity queryBySkillName(@Param("skillName") String skillName, @Param("userName") String userName);
    SkillConfigEntity queryBySkillNameAndScope(@Param("skillName") String skillName, @Param("scope") String scope);

    void insert(SkillConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    void deleteById(@Param("id") String id);
    void toggle(@Param("id") String id, @Param("enabled") Integer enabled, @Param("userName") String userName);
    void toggleById(@Param("id") String id, @Param("enabled") Integer enabled);
}