package com.github.hbq969.ai.zephyr.security.dao;

import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
@DS
public interface SecurityConfigDao {

    void createSecurityRulesTable();

    List<SecurityRuleEntity> queryAllByType(@Param("ruleType") String ruleType);
    List<SecurityRuleEntity> queryAll();
    void insert(SecurityRuleEntity entity);
    void deleteById(@Param("id") String id);
    void updateById(SecurityRuleEntity entity);
    void updateEnabled(SecurityRuleEntity entity);

    List<Map<String, Object>> queryCounts();

    void batchDelete(@Param("ids") List<String> ids);
}
