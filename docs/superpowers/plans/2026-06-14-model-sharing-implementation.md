# 模型配置共享机制 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为模型配置增加共享机制，管理员可将模型设为共享供全员使用，非管理员可查看和使用共享模型。

**Architecture:** 后端 `ModelConfigEntity` 加 `scope` 字段 + 新增 `UserModelPreferenceDao` 独立存储用户默认偏好（不修改他人模型记录）。前端 ModelSettings 主 tabs 从 type（对话/Embedding）改为 scope（我的/共享），InputArea 模型下拉分栏展示。

**Tech Stack:** Spring Boot 3.5.4 + MyBatis + H2/PostgreSQL/MySQL + Vue3 + TypeScript

---

### Task 1: DDL — 所有方言 Mapper XML 加 scope 列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`

- [ ] **Step 1: 在三个方言 Mapper XML 的 `createModelConfigsTable` 中加 scope 列 + 存量迁移**

在 `dimensions int,` 之前插入 scope 列 + 索引。同时在 DDL 前加 ALTER TABLE 语句处理存量数据库：

**embedded (H2):**
```xml
<update id="createModelConfigsTable">
  alter table zephyr_model_configs add column if not exists scope varchar(16) default 'user';
  update zephyr_model_configs set scope = 'user' where scope is null;
  create table if not exists zephyr_model_configs (
    id varchar(64) primary key,
    user_name varchar(64) not null,
    name varchar(128) not null,
    base_url varchar(512),
    api_key_encrypted text,
    max_context_tokens bigint,
    is_default smallint default 0,
    created_at bigint,
    updated_at bigint,
    model_type varchar(16) default 'llm',
    scope varchar(16) default 'user',
    dimensions int,
    params text
  );
  create index if not exists idx_zephyr_mc_user on zephyr_model_configs(user_name);
  create index if not exists idx_zephyr_mc_scope on zephyr_model_configs(scope);
</update>
```

**mysql:**
```xml
<update id="createModelConfigsTable">
  alter table zephyr_model_configs add column if not exists scope varchar(16) default 'user';
  update zephyr_model_configs set scope = 'user' where scope is null;
  create table if not exists zephyr_model_configs (
    id varchar(64) primary key,
    user_name varchar(64) not null,
    name varchar(128) not null,
    base_url varchar(512),
    api_key_encrypted text,
    max_context_tokens bigint,
    is_default smallint default 0,
    created_at bigint,
    updated_at bigint,
    model_type varchar(16) default 'llm',
    scope varchar(16) default 'user',
    dimensions int,
    params text
  );
  create index if not exists idx_zephyr_mc_user on zephyr_model_configs(user_name);
  create index if not exists idx_zephyr_mc_scope on zephyr_model_configs(scope);
</update>
```

**postgresql:**
```xml
<update id="createModelConfigsTable">
  alter table zephyr_model_configs add column if not exists scope varchar(16) default 'user';
  update zephyr_model_configs set scope = 'user' where scope is null;
  create table if not exists zephyr_model_configs (
    id varchar(64) primary key,
    user_name varchar(64) not null,
    name varchar(128) not null,
    base_url varchar(512),
    api_key_encrypted text,
    max_context_tokens bigint,
    is_default smallint default 0,
    created_at bigint,
    updated_at bigint,
    model_type varchar(16) default 'llm',
    scope varchar(16) default 'user',
    dimensions int,
    params text
  );
  create index if not exists idx_zephyr_mc_user on zephyr_model_configs(user_name);
  create index if not exists idx_zephyr_mc_scope on zephyr_model_configs(scope);
</update>
```

> 注：`ThrowUtils.call` 吞掉异常，即使 ALTER 失败（如 MySQL 不支持 `IF NOT EXISTS`），后续语句会被跳过。但 `CREATE TABLE IF NOT EXISTS` 对存量表也是安全的空操作，所以最坏情况是存量表的 scope 列未自动添加。MySQL 生产环境需手动执行一次 ALTER。

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/
git commit -m "chore: model_configs DDL 加 scope 列"
```

---

### Task 2: Entity — ModelConfigEntity 加 scope 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`

- [ ] **Step 1: 加 scope 字段**

在 `modelType` 字段后添加：
```java
private String scope = "user";
```

完整 Entity：
```java
package com.github.hbq969.ai.zephyr.config.dao.entity;

import lombok.Data;

@Data
public class ModelConfigEntity {
    private String id;
    private String userName;
    private String name;
    private String baseUrl;
    private String apiKeyEncrypted;
    private Integer isDefault;
    private Long createdAt;
    private Long updatedAt;
    private Long maxContextTokens;
    private String params;
    private String modelType;
    private String scope = "user";
    private Integer dimensions;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java
git commit -m "feat: ModelConfigEntity 加 scope 字段"
```

---

### Task 3: DML — Common ModelConfigMapper.xml 加 scope + 新增 SQL

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`

- [ ] **Step 1: 修改 INSERT 语句加 scope 列**

```xml
<insert id="insert">
  insert into zephyr_model_configs (id, user_name, name, base_url, api_key_encrypted, max_context_tokens, model_type, scope, dimensions, params, is_default, created_at, updated_at)
  values (#{id}, #{userName}, #{name}, #{baseUrl}, #{apiKeyEncrypted}, #{maxContextTokens}, #{modelType}, #{scope}, #{dimensions}, #{params}, #{isDefault}, #{createdAt}, #{updatedAt})
</insert>
```

- [ ] **Step 2: 修改 `queryByUserName` SELECT 加 scope 列 + WHERE 条件**

```xml
<select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens, model_type as modelType, scope, dimensions, params
  from zephyr_model_configs
  where user_name = #{userName} and scope = 'user'
  order by created_at desc
</select>
```

- [ ] **Step 3: 修改 `queryByType` SELECT 加 scope 列 + WHERE 条件**

```xml
<select id="queryByType" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted,
    is_default as isDefault, created_at as createdAt, updated_at as updatedAt,
    max_context_tokens as maxContextTokens, params, scope, model_type as modelType, dimensions
  from zephyr_model_configs
  where user_name = #{userName} and model_type = #{modelType} and scope = 'user'
  order by created_at desc
</select>
```

- [ ] **Step 4: 修改 `queryById` SELECT 加 scope 列**

```xml
<select id="queryById" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens, model_type as modelType, scope, dimensions, params
  from zephyr_model_configs where id = #{id}
</select>
```

- [ ] **Step 5: 新增 `queryShared`**

```xml
<select id="queryShared" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens, model_type as modelType, scope, dimensions, params
  from zephyr_model_configs
  where scope = 'shared'
  order by created_at desc
</select>
```

- [ ] **Step 6: 新增 `toggleScope`**

```xml
<update id="toggleScope">
  update zephyr_model_configs set scope = #{scope}, updated_at = #{updatedAt} where id = #{id}
</update>
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml
git commit -m "feat: common Mapper 加 scope 列 + queryShared/toggleScope SQL"
```

---

### Task 4: DAO — ModelConfigDao 加新方法

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java`

- [ ] **Step 1: 加新方法签名**

在现有方法后添加：
```java
List<ModelConfigEntity> queryShared();

void toggleScope(@Param("id") String id, @Param("scope") String scope, @Param("updatedAt") Long updatedAt);
```

完整文件：
```java
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
    List<ModelConfigEntity> queryShared();
    void insert(ModelConfigEntity entity);
    void update(ModelConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    ModelConfigEntity queryById(@Param("id") String id);
    void clearDefault(@Param("userName") String userName);
    void setDefault(@Param("id") String id, @Param("userName") String userName);
    void toggleScope(@Param("id") String id, @Param("scope") String scope, @Param("updatedAt") Long updatedAt);

    List<ModelConfigEntity> queryByType(@Param("userName") String userName, @Param("modelType") String modelType);

    ModelConfigEntity queryDefaultByType(@Param("modelType") String modelType);

    void clearDefaultByType(@Param("modelType") String modelType);

    @Update("update zephyr_model_configs set max_context_tokens = #{maxTokens}, updated_at = #{updatedAt} where id = #{id} and user_name = #{userName}")
    void updateMaxContextTokens(@Param("id") String id, @Param("maxTokens") Long maxTokens, @Param("updatedAt") Long updatedAt, @Param("userName") String userName);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java
git commit -m "feat: ModelConfigDao 加 queryShared/toggleScope 方法"
```

---

### Task 5: 新建 UserModelPreferenceEntity + DAO + Mapper XML（所有方言）

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/UserModelPreferenceEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/UserModelPreferenceDao.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/UserModelPreferenceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/UserModelPreferenceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/UserModelPreferenceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/UserModelPreferenceMapper.xml`

- [ ] **Step 1: 创建 Entity**

`UserModelPreferenceEntity.java`:
```java
package com.github.hbq969.ai.zephyr.config.dao.entity;

import lombok.Data;

@Data
public class UserModelPreferenceEntity {
    private String id;
    private String userName;
    private String modelType;
    private String modelId;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 创建 DAO**

`UserModelPreferenceDao.java`:
```java
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
```

- [ ] **Step 3: 创建 Common Mapper XML（仅跨方言安全的 DML）**

`common/UserModelPreferenceMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
  "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao">

  <select id="queryByUserAndType" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity">
    select id, user_name as userName, model_type as modelType, model_id as modelId,
           created_at as createdAt, updated_at as updatedAt
    from zephyr_user_model_prefs
    where user_name = #{userName} and model_type = #{modelType}
  </select>

  <delete id="deleteByModelId">
    delete from zephyr_user_model_prefs where model_id = #{modelId}
  </delete>

</mapper>
```

> 注意：`upsert` 是方言特定的，**不放在 common mapper 中**，由各方言 Mapper XML 分别声明。

- [ ] **Step 4: 创建 Embedded DDL Mapper XML + upsert**

`embedded/UserModelPreferenceMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao">

  <update id="createUserModelPrefsTable">
    create table if not exists zephyr_user_model_prefs (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      model_type varchar(16) not null default 'llm',
      model_id varchar(64) not null,
      created_at bigint not null,
      updated_at bigint not null
    );
    create unique index if not exists idx_user_model_prefs on zephyr_user_model_prefs(user_name, model_type);
  </update>

  <update id="upsert">
    merge into zephyr_user_model_prefs (id, user_name, model_type, model_id, created_at, updated_at)
    key (user_name, model_type)
    values (#{id}, #{userName}, #{modelType}, #{modelId}, #{createdAt}, #{updatedAt})
  </update>

</mapper>
```

mysql 和 postgresql 做同样的 DDL（H2 MERGE 语法替换为 mysql 的 `INSERT ... ON DUPLICATE KEY UPDATE` 和 postgresql 的 `INSERT ... ON CONFLICT ... DO UPDATE`）。

- [ ] **Step 5: 创建 MySQL DDL Mapper XML + upsert**

`mysql/UserModelPreferenceMapper.xml` DDL 同上（`varchar(64)`），upsert 用：
```xml
<update id="upsert">
  insert into zephyr_user_model_prefs (id, user_name, model_type, model_id, created_at, updated_at)
  values (#{id}, #{userName}, #{modelType}, #{modelId}, #{createdAt}, #{updatedAt})
  on duplicate key update model_id = #{modelId}, updated_at = #{updatedAt}
</update>
```

> MySQL 的 `ON DUPLICATE KEY UPDATE` 依赖 `(user_name, model_type)` 上的唯一索引（DDL 中已定义 `create unique index if not exists idx_user_model_prefs`）。

- [ ] **Step 6: 创建 PostgreSQL DDL Mapper XML + upsert**

`postgresql/UserModelPreferenceMapper.xml` DDL 同上（`varchar(64)`），upsert 用：
```xml
<update id="upsert">
  insert into zephyr_user_model_prefs (id, user_name, model_type, model_id, created_at, updated_at)
  values (#{id}, #{userName}, #{modelType}, #{modelId}, #{createdAt}, #{updatedAt})
  on conflict (user_name, model_type) do update set model_id = excluded.model_id, updated_at = excluded.updated_at
</update>
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/
git commit -m "feat: 新建 UserModelPreference Entity/DAO/Mapper（四个方言）"
```

---

### Task 6: InitialServiceImpl 注册 user_model_prefs 建表

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

- [ ] **Step 1: 注入 UserModelPreferenceDao 并注册建表**

在 `InitialServiceImpl` 中添加：
```java
@Resource
private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;
```

在 `tableCreate0()` 方法末尾的 `}` 前添加：
```java
com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_user_model_prefs",
        () -> userModelPreferenceDao.createUserModelPrefsTable());
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 注册 user_model_prefs 建表"
```

---

### Task 7: Service — ModelConfigServiceImpl 调整 list/setDefault + 加 toggleScope

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/service/ModelConfigService.java`

- [ ] **Step 1: ModelConfigService 接口加方法签名**

```java
void toggleScope(String id, String scope, String userName);
```

- [ ] **Step 2: 注入 UserModelPreferenceDao + 重写 list()**

在 `ModelConfigServiceImpl` 中注入：
```java
@Resource
private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;
```

改写 `list()` 方法（含偏好注入）：
```java
@Override
public List<ModelConfigEntity> list(String userName) {
    // 共享模型
    List<ModelConfigEntity> shared = modelConfigDao.queryShared();
    // 私有模型
    List<ModelConfigEntity> own = modelConfigDao.queryByUserName(userName);

    Map<String, ModelConfigEntity> dedup = new LinkedHashMap<>();
    for (ModelConfigEntity e : shared) {
        dedup.put(e.getName(), e);
    }
    // 私有覆盖同名共享
    for (ModelConfigEntity e : own) {
        dedup.put(e.getName(), e);
    }

    // 查用户偏好，注入 isDefault
    com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity llmPref =
            userModelPreferenceDao.queryByUserAndType(userName, "llm");
    com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity embPref =
            userModelPreferenceDao.queryByUserAndType(userName, "embedding");

    List<ModelConfigEntity> result = new ArrayList<>(dedup.values());
    for (ModelConfigEntity e : result) {
        String key = e.getApiKeyEncrypted();
        if (key != null && !key.isEmpty()) {
            e.setApiKeyEncrypted(maskApiKey(key));
        }
        // 偏好表匹配的模型设 isDefault=1（前端 loadModels 依赖此字段）
        String mt = e.getModelType() != null ? e.getModelType() : "llm";
        if ("llm".equals(mt) && llmPref != null && llmPref.getModelId().equals(e.getId())) {
            e.setIsDefault(1);
        } else if ("embedding".equals(mt) && embPref != null && embPref.getModelId().equals(e.getId())) {
            e.setIsDefault(1);
        }
    }
    return result;
}
```

- [ ] **Step 3: 改写 listByType()**

```java
@Override
public List<ModelConfigEntity> listByType(String modelType, String userName) {
    List<ModelConfigEntity> all = list(userName);
    List<ModelConfigEntity> filtered = new ArrayList<>();
    for (ModelConfigEntity e : all) {
        String mt = e.getModelType() != null ? e.getModelType() : "llm";
        if (modelType.equals(mt)) {
            filtered.add(e);
        }
    }
    return filtered;
}
```

- [ ] **Step 4: 改写 setDefault()（含可见性检查）**

```java
@Override
@Transactional
public void setDefault(String id, String userName) {
    ModelConfigEntity entity = modelConfigDao.queryById(id);
    if (entity == null) throw new RuntimeException("模型不存在");

    // 可见性检查：只能设自己私有模型或共享模型为默认
    if (!"shared".equals(entity.getScope()) && !userName.equals(entity.getUserName())) {
        throw new RuntimeException("无权访问此模型");
    }

    String modelType = entity.getModelType() != null ? entity.getModelType() : "llm";
    // 清掉用户私有模型中的默认标记（兼容过渡期）
    modelConfigDao.clearDefault(userName);

    // 写入用户偏好表
    com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity pref =
            new com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity();
    pref.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
    pref.setUserName(userName);
    pref.setModelType(modelType);
    pref.setModelId(id);
    pref.setCreatedAt(System.currentTimeMillis() / 1000);
    pref.setUpdatedAt(System.currentTimeMillis() / 1000);
    userModelPreferenceDao.upsert(pref);
}
```

- [ ] **Step 5: 加 toggleScope()（取消共享时清理其他用户偏好）**

```java
@Override
@Transactional
public void toggleScope(String id, String scope, String userName) {
    // admin 检查
    com.github.hbq969.code.sm.login.model.UserInfo ui =
            com.github.hbq969.code.sm.login.session.UserContext.getNoCheck();
    if (ui == null || !ui.isAdmin()) throw new RuntimeException("仅 admin 可管理共享模型");

    modelConfigDao.toggleScope(id, scope, System.currentTimeMillis() / 1000);

    // 取消共享时，清理其他用户指向该模型的偏好记录（避免悬空引用）
    if ("user".equals(scope)) {
        userModelPreferenceDao.deleteByModelId(id);
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/service/
git commit -m "feat: ModelConfigService 加共享合并逻辑 + toggleScope"
```

---

### Task 8: Controller — ModelConfigCtrl 加 toggle-scope 接口

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java`

- [ ] **Step 1: 加 toggleScope 接口**

在现有类中添加：
```java
@Operation(summary = "切换模型共享状态")
@RequestMapping(path = "/toggle-scope", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "modelConfig_toggleScope", apiDesc = "模型配置_切换共享状态")
public ReturnMessage<?> toggleScope(@RequestBody Map<String, String> body) {
    modelConfigService.toggleScope(body.get("id"), body.get("scope"), userName());
    return ReturnMessage.success("ok");
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java
git commit -m "feat: ModelConfigCtrl 加 toggle-scope 接口"
```

---

### Task 9: ContextBuilder 调整模型加载逻辑

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

- [ ] **Step 1: 注入 UserModelPreferenceDao 并改写模型加载逻辑**

在 `ContextBuilder` 中注入：
```java
@Resource
private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;
```

将 `build()` 方法中的模型加载部分（约第 122-128 行）替换为：
```java
// 1. 加载模型配置（合并共享 + 私有）
List<ModelConfigEntity> ownModels = modelConfigDao.queryByUserName(userName);
List<ModelConfigEntity> sharedModels = modelConfigDao.queryShared();

// 私有优先，共享补充（同名时私有覆盖共享）
Map<String, ModelConfigEntity> modelMap = new LinkedHashMap<>();
for (ModelConfigEntity m : ownModels) {
    modelMap.put(m.getName(), m);
}
for (ModelConfigEntity m : sharedModels) {
    modelMap.putIfAbsent(m.getName(), m);
}
List<ModelConfigEntity> allModels = new ArrayList<>(modelMap.values());

// 1.1 优先读用户偏好表（偏好 ID 在所有模型中查找，ownModels 优先）
ModelConfigEntity model = null;
String modelType = "llm";
com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity pref =
        userModelPreferenceDao.queryByUserAndType(userName, modelType);
if (pref != null) {
    String prefId = pref.getModelId();
    // 先查 ownModels（ID 查找不受去重影响），再查 allModels
    model = ownModels.stream().filter(m -> prefId.equals(m.getId())).findFirst()
            .orElseGet(() -> allModels.stream().filter(m -> prefId.equals(m.getId())).findFirst().orElse(null));
}
// 1.2 回退：用户私有默认 > 共享默认 > 第一个 llm
if (model == null) {
    model = ownModels.stream()
            .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
            .findFirst()
            .orElseGet(() -> allModels.stream()
                    .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
                    .findFirst().orElse(null));
}
if (model == null) {
    model = allModels.stream()
            .filter(m -> "llm".equals(m.getModelType()) || m.getModelType() == null)
            .findFirst()
            .orElse(null);
}
if (model == null) throw new RuntimeException("请先配置模型");
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: ContextBuilder 模型加载合并共享模型 + 用户偏好"
```

---

### Task 10: 前端 — types/chat.ts 加 scope 字段

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: ModelConfig 接口加 scope**

```typescript
export interface ModelConfig {
  id?: string
  name: string
  baseUrl?: string
  apiKey?: string
  isDefault: boolean
  maxContextTokens?: number
  params?: string
  modelType?: string
  dimensions?: number
  scope?: 'user' | 'shared'
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/types/chat.ts
git commit -m "feat: ModelConfig 接口加 scope 字段"
```

---

### Task 11: 前端 — store/settings.ts loadModels 映射 scope

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: loadModels 映射加 scope 字段**

将 `loadModels()` 中的映射改为：
```typescript
async function loadModels() {
  try {
    const res = await axios({ url: '/model-config/list', method: 'get' })
    if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
      const list: ModelConfig[] = res.data.body.map((m: any) => ({
        id: m.id,
        name: m.name,
        baseUrl: m.baseUrl,
        isDefault: m.isDefault === 1,
        apiKey: m.apiKeyEncrypted,
        maxContextTokens: m.maxContextTokens,
        params: m.params,
        modelType: m.modelType || 'llm',
        scope: m.scope || 'user',
        dimensions: m.dimensions
      }))
      models.value = list
      const def = list.find((m: ModelConfig) => m.isDefault)
      currentModel.value = def ? def.name : (list.length > 0 ? list[0].name : '无')
    }
  } catch (_) { /* keep defaults */ }
}
```

- [ ] **Step 2: 加 toggleModelScope 方法**

在 `loadUserInfo()` 后添加：
```typescript
async function toggleModelScope(id: string, scope: string) {
  await axios({ url: '/model-config/toggle-scope', method: 'post', data: { id, scope } })
  await loadModels()
}
```

在 return 对象中导出 `toggleModelScope`。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/store/settings.ts
git commit -m "feat: store 加 scope 映射 + toggleModelScope 方法"
```

---

### Task 12: 前端 — ModelSettings.vue Tab 重构

**Files:**
- Modify: `src/main/resources/static/src/views/settings/ModelSettings.vue`

- [ ] **Step 1: 将 tabs 从 llm/embedding 改为 user/shared**

关键改动：
1. `currentTab` 默认值改为 `'user'`
2. `llmModels` 和 `embeddingModels` computed 替换为 `userModels` 和 `sharedModels`
3. 模板 tabs 改为"我的模型"/"共享模型"
4. 每个卡片显示类型标签（对话/Embedding）+ scope badge
5. admin 可见共享 toggle 按钮，非 admin 隐藏编辑/删除
6. 每个 tab 内加类型筛选下拉

完整 script 改动：
```typescript
const currentTab = ref('user')

const userModels = computed(() =>
  settingsStore.models.filter(m => m.scope !== 'shared')
)
const sharedModels = computed(() =>
  settingsStore.models.filter(m => m.scope === 'shared')
)

// 类型筛选
const userTypeFilter = ref('')
const sharedTypeFilter = ref('')

const filteredUserModels = computed(() => {
  const list = userModels.value
  if (!userTypeFilter.value) {
    // 默认顺序：个人在上 → 按类型（llm 在前）
    return [...list].sort((a, b) => {
      const ta = a.modelType || 'llm'
      const tb = b.modelType || 'llm'
      if (ta !== tb) return ta === 'llm' ? -1 : 1
      return (a.name || '').localeCompare(b.name || '')
    })
  }
  return list.filter(m => (m.modelType || 'llm') === userTypeFilter.value)
})

const filteredSharedModels = computed(() => {
  const list = sharedModels.value
  if (!sharedTypeFilter.value) {
    return [...list].sort((a, b) => {
      const ta = a.modelType || 'llm'
      const tb = b.modelType || 'llm'
      if (ta !== tb) return ta === 'llm' ? -1 : 1
      return (a.name || '').localeCompare(b.name || '')
    })
  }
  return list.filter(m => (m.modelType || 'llm') === sharedTypeFilter.value)
})

async function toggleScope(m: any) {
  if (!m.id) return
  const newScope = m.scope === 'shared' ? 'user' : 'shared'
  await settingsStore.toggleModelScope(m.id, newScope)
}
```

- [ ] **Step 2: 模板改为 scope tabs + 类型筛选 + 共享 toggle**

用户 tab 模板（我的模型，放在上面）：
```html
<el-tab-pane :label="'我的模型 (' + userModels.length + ')'" name="user">
  <div v-if="userModels.length > 0" class="type-filter-bar">
    <el-select v-model="userTypeFilter" class="type-filter-select" :placeholder="'类型筛选'" clearable>
      <el-option label="全部" value="" />
      <el-option label="对话模型" value="llm" />
      <el-option label="Embedding 模型" value="embedding" />
    </el-select>
  </div>
  <div v-for="m in filteredUserModels" :key="m.name" class="setting-row">
    <div class="row-left">
      <Icon icon="lucide:cpu" class="row-icon" />
      <div>
        <div class="row-title">
          {{ m.name }}
          <span class="model-type-tag" :class="(m.modelType || 'llm') === 'llm' ? 'tag-llm' : 'tag-embedding'">
            {{ (m.modelType || 'llm') === 'llm' ? '对话' : 'Embedding' }}
          </span>
        </div>
        <div v-if="m.baseUrl" class="row-sub">{{ m.baseUrl }}</div>
        <div v-if="m.maxContextTokens" class="row-sub ctx-info">{{ langData.modelConfig_contextLabel }}: {{ (m.maxContextTokens / 1024).toFixed(0) }}K</div>
      </div>
    </div>
    <div class="row-right">
      <button class="action-icon" @click="startEdit(m)" :title="langData.btnEdit"><Icon icon="lucide:pencil" /></button>
      <button class="action-icon danger" @click="m.id && removeModel(m.id)" :title="langData.btnDelete"><Icon icon="lucide:trash-2" /></button>
      <el-tooltip v-if="settingsStore.isAdmin" :content="'共享'">
        <button class="action-icon" @click="toggleScope(m)" :title="'设为共享'">
          <Icon icon="lucide:share-2" />
        </button>
      </el-tooltip>
      <button v-if="settingsStore.currentModel !== m.name" class="set-btn" @click="onSetCurrent(m.name)">{{ langData.modelConfig_use }}</button>
      <span v-else class="current-badge">{{ langData.modelConfig_current }}</span>
    </div>
  </div>
  <div v-if="userModels.length === 0" class="empty-state" style="text-align:center;padding:40px 0;color:var(--el-text-color-secondary)">
    <p>暂未配置个人模型</p>
  </div>
</el-tab-pane>
```

共享 tab 模板：
```html
<el-tab-pane :label="'共享模型 (' + sharedModels.length + ')'" name="shared">
  <div v-if="sharedModels.length > 0" class="type-filter-bar">
    <el-select v-model="sharedTypeFilter" class="type-filter-select" :placeholder="'类型筛选'" clearable>
      <el-option label="全部" value="" />
      <el-option label="对话模型" value="llm" />
      <el-option label="Embedding 模型" value="embedding" />
    </el-select>
  </div>
  <div v-for="m in filteredSharedModels" :key="m.name" class="setting-row">
    <div class="row-left">
      <Icon icon="lucide:cpu" class="row-icon" />
      <div>
        <div class="row-title">
          {{ m.name }}
          <span class="model-type-tag" :class="(m.modelType || 'llm') === 'llm' ? 'tag-llm' : 'tag-embedding'">
            {{ (m.modelType || 'llm') === 'llm' ? '对话' : 'Embedding' }}
          </span>
          <span class="badge badge-scope-shared">共享</span>
        </div>
        <div v-if="m.baseUrl" class="row-sub">{{ m.baseUrl }}</div>
        <div v-if="m.maxContextTokens" class="row-sub ctx-info">{{ langData.modelConfig_contextLabel }}: {{ (m.maxContextTokens / 1024).toFixed(0) }}K</div>
      </div>
    </div>
    <div class="row-right">
      <template v-if="settingsStore.isAdmin">
        <button class="action-icon" @click="startEdit(m)" :title="langData.btnEdit"><Icon icon="lucide:pencil" /></button>
        <button class="action-icon danger" @click="m.id && removeModel(m.id)" :title="langData.btnDelete"><Icon icon="lucide:trash-2" /></button>
        <el-tooltip :content="'取消共享'">
          <button class="action-icon" @click="toggleScope(m)">
            <Icon icon="lucide:lock" />
          </button>
        </el-tooltip>
      </template>
      <button v-if="settingsStore.currentModel !== m.name" class="set-btn" @click="onSetCurrent(m.name)">{{ langData.modelConfig_use }}</button>
      <span v-else class="current-badge">{{ langData.modelConfig_current }}</span>
    </div>
  </div>
  <div v-if="sharedModels.length === 0" class="empty-state" style="text-align:center;padding:40px 0;color:var(--el-text-color-secondary)">
    <p>暂无共享模型</p>
  </div>
</el-tab-pane>
```

- [ ] **Step 3: 删除旧的 llm/embedding tab 相关代码**

删除 `llmModels`、`embeddingModels` computed，删除旧的 `el-tab-pane` 块。

- [ ] **Step 4: 加类型筛选 + scope badge 样式**

在 `<style scoped>` 中添加：
```css
.type-filter-bar { display: flex; justify-content: flex-end; margin-bottom: 8px; }
.type-filter-select { width: 160px; }
.badge-scope-shared { display: inline-block; padding: 2px 8px; border-radius: 99px; font-size: 11px; font-weight: 500; background: rgba(204,120,92,0.12); color: var(--el-color-primary); margin-left: 6px; vertical-align: middle; }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/src/views/settings/ModelSettings.vue
git commit -m "feat: ModelSettings Tab 重构为 scope 维度（我的/共享）"
```

---

### Task 13: 前端 — InputArea.vue 模型选择器分栏

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 加 sharedModels 和 userModels computed**

在 `<script>` 中 `chatModels` 定义后添加：
```typescript
const sharedModels = computed(() => chatModels.value.filter((m: any) => m.scope === 'shared'))
const userModels = computed(() => chatModels.value.filter((m: any) => m.scope !== 'shared'))
```

- [ ] **Step 2: 模板拆分共享/我的两个 section**

将模型选择下拉（约第 588-599 行）替换为：
```html
<div v-if="showModelList" class="pick-dropdown model-dropdown" @click.stop>
  <template v-if="sharedModels.length > 0">
    <div class="kb-section-label">共享模型</div>
    <div v-for="m in sharedModels" :key="m.name" class="pick-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
      <div class="model-option-main">
        <span class="model-name">{{ m.name }}</span>
        <span class="model-tags">
          <span class="skill-scope-badge scope-shared">共享</span>
          <span v-if="hasThinking(m.params)" class="model-tag think-tag">{{ langData.inputArea_thinking }}</span>
          <span v-if="m.maxContextTokens" class="model-tag ctx-tag">{{ formatContextSize(m.maxContextTokens) }}</span>
        </span>
      </div>
      <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
    </div>
  </template>
  <div v-if="sharedModels.length > 0 && userModels.length > 0" class="kb-section-divider"></div>
  <template v-if="userModels.length > 0">
    <div class="kb-section-label">我的模型</div>
    <div v-for="m in userModels" :key="m.name" class="pick-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
      <div class="model-option-main">
        <span class="model-name">{{ m.name }}</span>
        <span class="model-tags">
          <span class="skill-scope-badge scope-user">个人</span>
          <span v-if="hasThinking(m.params)" class="model-tag think-tag">{{ langData.inputArea_thinking }}</span>
          <span v-if="m.maxContextTokens" class="model-tag ctx-tag">{{ formatContextSize(m.maxContextTokens) }}</span>
        </span>
      </div>
      <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
    </div>
  </template>
</div>
```

- [ ] **Step 3: 模型下拉宽度微调**

```css
.model-dropdown { min-width: 320px; }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: InputArea 模型选择器分栏展示（共享/我的）"
```

---

### Task 14: 灰度迁移 SQL — 已有环境加 scope 列

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 在三个 SQL 文件中添加增量 DDL**

查询 `mybatis.config-location` 确定方言 → 此处 H2/PostgreSQL 均可：

```sql
-- 模型配置共享：scope 列
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS scope VARCHAR(16) DEFAULT 'user';

-- 用户模型偏好表
CREATE TABLE IF NOT EXISTS zephyr_user_model_prefs (
    id VARCHAR(12) PRIMARY KEY,
    user_name VARCHAR(64) NOT NULL,
    model_type VARCHAR(16) NOT NULL DEFAULT 'llm',
    model_id VARCHAR(12) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
```

注：PostgreSQL 的 `COLUMN IF NOT EXISTS` 仅在 PG 9.6+ 支持；H2 和 MySQL 不支持该语法。对于 H2，使用 H2 兼容写法或直接在代码中做一次尝试性迁移。

由于此项目 me/dev 环境用 H2，增量 SQL 会影响所有环境。为确保跨方言安全，改为在 H2 SQL 中用 Java 代码方式处理。实际只需在 Mapper XML DDL 中加列（已有 `if not exists` 保障），已在 Task 1 覆盖。SQL 文件中只加 `CREATE TABLE IF NOT EXISTS zephyr_user_model_prefs`。

zephyr-zh-CN.sql 末尾追加：
```sql
CREATE TABLE IF NOT EXISTS zephyr_user_model_prefs (
    id VARCHAR(64) PRIMARY KEY,
    user_name VARCHAR(64) NOT NULL,
    model_type VARCHAR(16) NOT NULL DEFAULT 'llm',
    model_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_model_prefs ON zephyr_user_model_prefs(user_name, model_type);
```

> 唯一索引是 MySQL `ON DUPLICATE KEY UPDATE` 和 PostgreSQL `ON CONFLICT` 的前置条件，必须在 SQL 迁移中与建表一起执行。

- [ ] **Step 2: 对 en-US 和 ja-JP SQL 做同样改动**

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/zephyr-*.sql
git commit -m "feat: SQL 增量脚本加 user_model_prefs 建表"
```

---

### Task 15: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: 用 admin 创建对话模型并设为共享**

```bash
# 先查 admin 创建的模型 id
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/model-config/list"

# 设为共享
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/model-config/toggle-scope" \
  -d '{"id":"<模型ID>","scope":"shared"}'
```

- [ ] **Step 3: 验证 list 接口返回合并后的模型列表（普通用户也能看到共享模型）**

```bash
curl -u user1:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/model-config/list"
```

- [ ] **Step 4: 构建前端并启动浏览器验证**

```bash
cd src/main/resources/static && npm run build && mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

验证清单：
- [ ] 模型管理页 tabs 显示"我的模型"/"共享模型"
- [ ] 个人模型在上，共享在下
- [ ] 卡片有类型标签（对话/Embedding）
- [ ] 共享模型卡片有"共享" badge
- [ ] admin 可见共享/取消共享按钮
- [ ] 非 admin 看不到编辑/删除/共享切换按钮
- [ ] 聊天输入框模型下拉分栏展示（共享模型/我的模型）
- [ ] 任意用户可将共享模型设为默认并使用

---

### Task 16: graphify 更新

- [ ] **Step 1: 运行 graphify 更新知识图谱**

```bash
graphify update .
```

---

## 变更文件汇总

| # | 文件 | 操作 |
|---|------|------|
| 1 | `config/dao/mapper/{embedded,mysql,postgresql}/ModelConfigMapper.xml` | 改 DDL 加 scope 列 |
| 2 | `config/dao/entity/ModelConfigEntity.java` | 加 scope 字段 |
| 3 | `config/dao/mapper/common/ModelConfigMapper.xml` | DML 加 scope + 新 SQL |
| 4 | `config/dao/ModelConfigDao.java` | 加 queryShared/toggleScope |
| 5 | `config/dao/entity/UserModelPreferenceEntity.java` | 新建 |
| 6 | `config/dao/UserModelPreferenceDao.java` | 新建 |
| 7 | `config/dao/mapper/{common,embedded,mysql,postgresql}/UserModelPreferenceMapper.xml` | 新建（四个） |
| 8 | `service/impl/InitialServiceImpl.java` | 注册建表 |
| 9 | `config/service/ModelConfigService.java` | 加接口方法 |
| 10 | `config/service/impl/ModelConfigServiceImpl.java` | 改 list/setDefault + toggleScope |
| 11 | `config/ctrl/ModelConfigCtrl.java` | 加 toggle-scope 接口 |
| 12 | `chat/service/ContextBuilder.java` | 改模型加载逻辑 |
| 13 | `static/src/types/chat.ts` | ModelConfig 加 scope |
| 14 | `static/src/store/settings.ts` | scope 映射 + toggleModelScope |
| 15 | `static/src/views/settings/ModelSettings.vue` | Tab 重构 |
| 16 | `static/src/views/chat/InputArea.vue` | 模型选择分栏 |
| 17 | `resources/zephyr-{zh-CN,en-US,ja-JP}.sql` | 增量 DDL |
