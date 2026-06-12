# 知识库功能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现从文档导入到 AI 检索的完整 RAG 知识库功能

**Architecture:** 在现有模型配置中扩展 Embedding 类型 → 建立知识库/文档数据表 → 实现 Tika 解析 + 递归切分 + Chroma 向量化流水线 → ContextBuilder 注入 search_knowledge 工具 → 前端管理页 + 聊天勾选

**Tech Stack:** Spring Boot 3.5.4 + MyBatis + Apache Tika 3.1.0 + OkHttp + Chroma HTTP API + Vue 3 + Element Plus

**执行顺序：** 01 → 02 → 06 → 03 → 04 → 05

---

### Task 1: Spec 01 — 模型配置表加字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: Entity 加字段**

在 `ModelConfigEntity.java` 末尾加两个字段：
```java
private String modelType;
private Integer dimensions;
```

- [ ] **Step 2: 三方言 DDL 同步加列**

在 `postgresql/ModelConfigMapper.xml`、`mysql/ModelConfigMapper.xml`、`embedded/ModelConfigMapper.xml` 的 `createModelConfigsTable` 中，`params text` 之前加：
```xml
, model_type varchar(16) default 'llm'
, dimensions int
```

- [ ] **Step 3: common Mapper XML 加字段**

在 `common/ModelConfigMapper.xml` 中：
- `queryByUserName` / `queryById` 的 select 语句加：`, model_type as modelType, dimensions`
- `insert` 的列和值加：`, model_type, dimensions` 和 `, #{modelType}, #{dimensions}`
- `update` 加：
```xml
<if test="modelType != null">, model_type = #{modelType}</if>
<if test="dimensions != null">, dimensions = #{dimensions}</if>
```

- [ ] **Step 4: 增量 SQL 三个语言文件**

在 `zephyr-zh-CN.sql`、`zephyr-en-US.sql`、`zephyr-ja-JP.sql` 末尾加：
```sql
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS model_type varchar(16) DEFAULT 'llm';
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS dimensions int DEFAULT NULL;
```

- [ ] **Step 5: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java \
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/ \
  src/main/resources/zephyr-*.sql
git commit -m "feat: 模型配置表加 model_type 和 dimensions 字段，支持 Embedding 模型"
```

---

### Task 2: Spec 01 — 模型配置 API 调整

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/service/ModelConfigService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: DAO 加方法**

在 `ModelConfigDao.java` 加：
```java
List<ModelConfigEntity> queryByType(@Param("userName") String userName, @Param("modelType") String modelType);

ModelConfigEntity queryDefaultByType(@Param("modelType") String modelType);

void clearDefaultByType(@Param("modelType") String modelType);
```

- [ ] **Step 2: Mapper XML 加 SQL**

在 `common/ModelConfigMapper.xml` 加：
```xml
<select id="queryByType" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted,
    is_default as isDefault, created_at as createdAt, updated_at as updatedAt,
    max_context_tokens as maxContextTokens, params, model_type as modelType, dimensions
  from zephyr_model_configs
  where user_name = #{userName} and model_type = #{modelType}
  order by created_at desc
</select>

<select id="queryDefaultByType" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted,
    is_default as isDefault, created_at as createdAt, updated_at as updatedAt,
    max_context_tokens as maxContextTokens, params, model_type as modelType, dimensions
  from zephyr_model_configs
  where model_type = #{modelType} and is_default = 1 limit 1
</select>

<update id="clearDefaultByType">
  update zephyr_model_configs set is_default = 0 where model_type = #{modelType}
</update>
```

- [ ] **Step 3: Service 加方法**

在 `ModelConfigService.java` 加：
```java
List<ModelConfigEntity> listByType(String modelType, String userName);
```

在 `ModelConfigServiceImpl.java` 实现：
```java
@Override
public List<ModelConfigEntity> listByType(String modelType, String userName) {
    return modelConfigDao.queryByType(userName, modelType);
}
```

- [ ] **Step 4: Controller 加类型过滤**

在 `ModelConfigCtrl.java` 的 `list()` 方法中，加 `@RequestParam(required = false) String modelType` 参数。如果 `modelType` 非空，调用 `listByType`，否则走原来的 `queryByUserName`。

- [ ] **Step 5: set-default 区分类型**

在 `setDefault` 方法中：先查目标模型的 `modelType`，对该类型的模型调用 `clearDefaultByType`（而非清所有类型的默认），再设默认。

- [ ] **Step 6: 编译验证**

```bash
mvn compile
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/
git commit -m "feat: 模型配置 API 支持 modelType 过滤和分类设默认"
```

---

### Task 3: Spec 01 — 前端模型配置页适配

**Files:**
- Modify: `src/main/resources/static/src/views/settings/ModelSettings.vue`
- Modify: `src/main/resources/static/src/views/chat/SettingsPanel.vue`
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: 模型列表加类型 tag**

在 `ModelSettings.vue` 新建/编辑表单中：
- 加"模型类型"下拉：`el-select` 绑定 `form.modelType`，选项 "对话模型 (llm)"、"Embedding 模型 (embedding)"
- 当 `form.modelType === 'embedding'` 时，显示"向量维度"输入框 `form.dimensions`

模型列表每行加类型标签：
```html
<el-tag size="small" :type="m.modelType === 'embedding' ? 'success' : ''">
  {{ m.modelType === 'embedding' ? 'Embedding' : '对话' }}
</el-tag>
```

- [ ] **Step 2: 聊天页模型选择器只显示 LLM**

在 `SettingsPanel.vue` 或其他模型选择组件中，显示模型列表时过滤：
```typescript
const chatModels = computed(() => settingsStore.models.filter(m => !m.modelType || m.modelType === 'llm'))
```

- [ ] **Step 3: store 更新**

在 `settings.ts` 的 `loadModels()` 中，映射加 `modelType` 和 `dimensions` 字段：
```typescript
modelType: m.modelType || 'llm',
dimensions: m.dimensions
```

- [ ] **Step 4: 构建前端**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 5: 后端验证**

启动后端，curl 验证：
```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/list?modelType=embedding"
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/src/
git commit -m "feat: 前端模型配置页支持对话/Embedding 类型区分"
```

---

### Task 4: Spec 02 — 新表 DDL + Entity + DAO

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeBaseEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeDocEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

- [ ] **Step 1: 创建 KnowledgeBaseEntity**

```java
package com.github.hbq969.ai.zephyr.knowledge.dao.entity;

import lombok.Data;

@Data
public class KnowledgeBaseEntity {
    private String id;
    private String userName;
    private String name;
    private String description;
    private String embedModelId;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 创建 KnowledgeDocEntity**

```java
package com.github.hbq969.ai.zephyr.knowledge.dao.entity;

import lombok.Data;

@Data
public class KnowledgeDocEntity {
    private String id;
    private String kbId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private Long createdAt;
}
```

- [ ] **Step 3: 创建 KnowledgeDao**

```java
package com.github.hbq969.ai.zephyr.knowledge.dao;

import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface KnowledgeDao {
    // DDL
    void createKnowledgeBaseTable();
    void createKnowledgeDocTable();
    void createConversationKbTable();

    // Knowledge Base CRUD
    List<KnowledgeBaseEntity> queryKbByUserName(@Param("userName") String userName);
    KnowledgeBaseEntity queryKbById(@Param("id") String id);
    List<KnowledgeBaseEntity> queryKbByIds(@Param("ids") List<String> ids);
    void insertKb(KnowledgeBaseEntity entity);
    void updateKb(KnowledgeBaseEntity entity);
    void deleteKb(@Param("id") String id);

    // Document CRUD
    List<KnowledgeDocEntity> queryDocsByKbId(@Param("kbId") String kbId);
    KnowledgeDocEntity queryDocById(@Param("id") String id);
    void insertDoc(KnowledgeDocEntity entity);
    void updateDocStatus(@Param("id") String id, @Param("status") String status,
                         @Param("chunkCount") Integer chunkCount, @Param("errorMsg") String errorMsg);
    void deleteDoc(@Param("id") String id);
    void deleteDocsByKbId(@Param("kbId") String kbId);

    // Conversation-KB association
    List<String> queryKbIdsByConversation(@Param("conversationId") String conversationId);
    void insertConversationKb(@Param("conversationId") String conversationId, @Param("kbId") String kbId);
    void deleteConversationKb(@Param("conversationId") String conversationId);
}
```

- [ ] **Step 4: 三方言 DDL Mapper XML**

`postgresql/KnowledgeMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao">

  <update id="createKnowledgeBaseTable">
    create table if not exists zephyr_knowledge_base (
      id varchar(36) primary key,
      user_name varchar(64) not null,
      name varchar(128) not null,
      description varchar(512),
      embed_model_id varchar(36),
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_zephyr_kb_user on zephyr_knowledge_base(user_name);
  </update>

  <update id="createKnowledgeDocTable">
    create table if not exists zephyr_knowledge_doc (
      id varchar(36) primary key,
      kb_id varchar(36) not null,
      file_name varchar(256),
      file_type varchar(16),
      file_size bigint,
      chunk_count int default 0,
      status varchar(16) default 'processing',
      error_msg varchar(512),
      created_at bigint
    );
    create index if not exists idx_zephyr_kd_kb on zephyr_knowledge_doc(kb_id);
  </update>

  <update id="createConversationKbTable">
    create table if not exists zephyr_conversation_kb (
      conversation_id varchar(36) not null,
      kb_id varchar(36) not null,
      primary key (conversation_id, kb_id)
    );
  </update>

</mapper>
```

`mysql/` 和 `embedded/` 目录用同样内容（注意 H2 的 `create index if not exists` 语法不变）。

- [ ] **Step 5: common Mapper XML**

`common/KnowledgeMapper.xml` — 所有 CRUD SQL。关键 SQL：

```xml
<mapper namespace="com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao">

  <select id="queryKbByUserName" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base where user_name = #{userName} order by created_at desc
  </select>

  <select id="queryKbByIds" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base where id in
    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
  </select>

  <!-- 其余 insert/update/delete/selectOne 按标准 MyBatis 写法补全 -->
</mapper>
```

> **注：** 完整 SQL 在实现时补全（insertKb, updateKb, deleteKb, queryKbById, queryDocsByKbId, queryDocById, insertDoc, updateDocStatus, deleteDoc, deleteDocsByKbId, queryKbIdsByConversation, insertConversationKb, deleteConversationKb），模式与现有 `common/ModelConfigMapper.xml` 一致。

- [ ] **Step 6: InitialServiceImpl 注册表创建**

在 `InitialServiceImpl.java` 中加：
```java
@Resource
private com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao knowledgeDao;
```

在 `tableCreate0()` 中加：
```java
ThrowUtils.call("zephyr_knowledge_base", () -> knowledgeDao.createKnowledgeBaseTable());
ThrowUtils.call("zephyr_knowledge_doc", () -> knowledgeDao.createKnowledgeDocTable());
ThrowUtils.call("zephyr_conversation_kb", () -> knowledgeDao.createConversationKbTable());
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/ \
  src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 知识库/文档/对话关联三张表 DDL + Entity + DAO"
```

---

### Task 5: Spec 02 — 知识库 CRUD Controller + Service

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/model/KnowledgeVO.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java`

- [ ] **Step 1: 创建 KnowledgeVO**

```java
package com.github.hbq969.ai.zephyr.knowledge.model;

import lombok.Data;

@Data
public class KnowledgeVO {
    private String id;
    private String name;
    private String description;
    private String embedModelId;
    private String embedModelName;
    private int docCount;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 创建 KnowledgeService 接口**

```java
package com.github.hbq969.ai.zephyr.knowledge.service;

import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import java.util.List;
import java.util.Map;

public interface KnowledgeService {
    List<KnowledgeVO> listKb(String userName);
    KnowledgeBaseEntity createKb(Map<String, String> body, String userName);
    void updateKb(Map<String, String> body, String userName);
    void deleteKb(String id, String userName);

    List<KnowledgeDocEntity> listDocs(String kbId);
    void deleteDoc(String id);

    List<String> getConversationKbIds(String conversationId);
    void saveConversationKbIds(String conversationId, List<String> kbIds);
}
```

- [ ] **Step 3: 实现 KnowledgeServiceImpl**

```java
package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    @Resource private KnowledgeDao knowledgeDao;
    @Resource private ModelConfigDao modelConfigDao;

    @Override
    public List<KnowledgeVO> listKb(String userName) {
        List<KnowledgeBaseEntity> kbs = knowledgeDao.queryKbByUserName(userName);
        List<KnowledgeVO> vos = new ArrayList<>();
        for (KnowledgeBaseEntity kb : kbs) {
            KnowledgeVO vo = new KnowledgeVO();
            vo.setId(kb.getId());
            vo.setName(kb.getName());
            vo.setDescription(kb.getDescription());
            vo.setEmbedModelId(kb.getEmbedModelId());
            if (kb.getEmbedModelId() != null) {
                var model = modelConfigDao.queryById(kb.getEmbedModelId());
                if (model != null) vo.setEmbedModelName(model.getName());
            }
            List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kb.getId());
            vo.setDocCount(docs.size());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public KnowledgeBaseEntity createKb(Map<String, String> body, String userName) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEmbedModelId(body.get("embedModelId"));
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        knowledgeDao.insertKb(entity);
        return entity;
    }

    @Override
    public void updateKb(Map<String, String> body, String userName) {
        KnowledgeBaseEntity entity = knowledgeDao.queryKbById(body.get("id"));
        if (entity == null) throw new RuntimeException("知识库不存在");
        entity.setName(body.get("name"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEmbedModelId(body.get("embedModelId"));
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.updateKb(entity);
    }

    @Override
    @Transactional
    public void deleteKb(String id, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(id);
        if (kb == null) throw new RuntimeException("知识库不存在");
        // 级联删除由 Spec 03 补充（Chroma collection + 磁盘文件）
        knowledgeDao.deleteDocsByKbId(id);
        knowledgeDao.deleteKb(id);
    }

    @Override
    public List<KnowledgeDocEntity> listDocs(String kbId) {
        return knowledgeDao.queryDocsByKbId(kbId);
    }

    @Override
    public void deleteDoc(String id) {
        knowledgeDao.deleteDoc(id);
    }

    @Override
    public List<String> getConversationKbIds(String conversationId) {
        return knowledgeDao.queryKbIdsByConversation(conversationId);
    }

    @Override
    public void saveConversationKbIds(String conversationId, List<String> kbIds) {
        knowledgeDao.deleteConversationKb(conversationId);
        if (kbIds != null) {
            for (String kbId : kbIds) {
                knowledgeDao.insertConversationKb(conversationId, kbId);
            }
        }
    }
}
```

- [ ] **Step 4: 创建 KnowledgeCtrl**

```java
package com.github.hbq969.ai.zephyr.knowledge.ctrl;

import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "知识库管理")
@RestController
@RequestMapping(path = "/zephyr-ui/knowledge")
public class KnowledgeCtrl {

    @Resource
    private KnowledgeService knowledgeService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "知识库列表")
    @RequestMapping(path = "/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "kb_list", apiDesc = "知识库管理_知识库列表")
    public ReturnMessage<?> listKb() {
        return ReturnMessage.success(knowledgeService.listKb(userName()));
    }

    @Operation(summary = "创建知识库")
    @RequestMapping(path = "/kb/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "kb_create", apiDesc = "知识库管理_创建知识库")
    public ReturnMessage<?> createKb(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(knowledgeService.createKb(body, userName()));
    }

    @Operation(summary = "编辑知识库")
    @RequestMapping(path = "/kb/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "kb_update", apiDesc = "知识库管理_编辑知识库")
    public ReturnMessage<?> updateKb(@RequestBody Map<String, String> body) {
        knowledgeService.updateKb(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除知识库")
    @RequestMapping(path = "/kb/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "kb_delete", apiDesc = "知识库管理_删除知识库")
    public ReturnMessage<?> deleteKb(@RequestBody Map<String, String> body) {
        knowledgeService.deleteKb(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "文档列表")
    @RequestMapping(path = "/doc/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_list", apiDesc = "知识库管理_文档列表")
    public ReturnMessage<?> listDocs(@RequestParam String kbId) {
        return ReturnMessage.success(knowledgeService.listDocs(kbId));
    }

    @Operation(summary = "删除文档")
    @RequestMapping(path = "/doc/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_delete", apiDesc = "知识库管理_删除文档")
    public ReturnMessage<?> deleteDoc(@RequestBody Map<String, String> body) {
        knowledgeService.deleteDoc(body.get("id"));
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "获取对话勾选的知识库")
    @RequestMapping(path = "/conversation/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conv_kb_list", apiDesc = "知识库管理_对话勾选列表")
    public ReturnMessage<?> getConversationKbs(@RequestParam String conversationId) {
        return ReturnMessage.success(knowledgeService.getConversationKbIds(conversationId));
    }

    @Operation(summary = "保存对话勾选的知识库")
    @RequestMapping(path = "/conversation/kb/save", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conv_kb_save", apiDesc = "知识库管理_保存对话勾选")
    public ReturnMessage<?> saveConversationKbs(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) body.get("kbIds");
        knowledgeService.saveConversationKbIds(body.get("conversationId").toString(), kbIds);
        return ReturnMessage.success("ok");
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn compile
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/
git commit -m "feat: 知识库 CRUD Controller + Service + 对话关联 API"
```

---

### Task 6: Spec 06 — Chroma 配置 + ZephyrConfigProperties 扩展

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: ZephyrConfigProperties 加 Knowledge 配置**

在 `ZephyrConfigProperties.java` 的 `memory` 字段后加：
```java
/** 知识库相关配置 */
private Knowledge knowledge = new Knowledge();

@Data
public static class Knowledge {
    /** Chroma 向量数据库 */
    private Chroma chroma = new Chroma();
    /** 文档存储根目录 */
    private String dataDir = System.getProperty("user.home") + "/.zephyr/knowledge";

    @Data
    public static class Chroma {
        /** embedded / server */
        private String mode = "embedded";
        /** embedded 模式数据目录 */
        private String dataDir = System.getProperty("user.home") + "/.zephyr/chroma";
        /** embedded 模式端口 */
        private int port = 18951;
        /** server 模式地址 */
        private String baseUrl;
    }
}
```

- [ ] **Step 2: application.yml 加默认值**

```yaml
zephyr:
  knowledge:
    data-dir: ${user.home}/.zephyr/knowledge
    chroma:
      mode: embedded
      data-dir: ${user.home}/.zephyr/chroma
      port: 18951
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java \
  src/main/resources/application.yml
git commit -m "feat: ZephyrConfigProperties 扩展知识库和 Chroma 配置"
```

---

### Task 7: Spec 03 — Maven 依赖 + TikaParser + TextSplitter

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TikaParser.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextSplitter.java`

- [ ] **Step 1: pom.xml 加 Tika 依赖**

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.1.0</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.1.0</version>
</dependency>
```

- [ ] **Step 2: 创建 TikaParser**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;

@Component
public class TikaParser {
    private final Tika tika = new Tika();

    public String parse(InputStream in, String fileName) throws IOException, TikaException {
        return tika.parseToString(in);
    }
}
```

- [ ] **Step 3: 创建 TextSplitter**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TextSplitter {
    private static final List<String> SEPARATORS = Arrays.asList("\n\n", "\n", "。", " ", "");
    private final int chunkSize;
    private final int overlap;

    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public TextSplitter() {
        this(800, 150);
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;
        splitRecursive(text, chunks);
        return chunks;
    }

    private void splitRecursive(String text, List<String> chunks) {
        if (text.length() <= chunkSize) {
            if (!text.trim().isEmpty()) chunks.add(text.trim());
            return;
        }
        String sep = findSeparator(text);
        if (sep == null || sep.isEmpty()) {
            // 按 chunkSize 硬切
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + chunkSize, text.length());
                chunks.add(text.substring(start, end).trim());
                start = end - overlap;
            }
            return;
        }
        String[] parts = text.split(java.util.regex.Pattern.quote(sep), -1);
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() > 0
                    ? current + sep + part
                    : part;
            if (candidate.length() > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                // overlap: 保留当前块的最后一个"段落"
                current = new StringBuilder(part);
            } else {
                if (current.length() > 0) current.append(sep);
                current.append(part);
            }
        }
        if (current.length() > 0) {
            String s = current.toString().trim();
            if (!s.isEmpty()) {
                if (s.length() > chunkSize) splitRecursive(s, chunks);
                else chunks.add(s);
            }
        }
    }

    private String findSeparator(String text) {
        for (String sep : SEPARATORS) {
            if (text.contains(sep)) return sep;
        }
        return null;
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile
```

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/
git commit -m "feat: Apache Tika 文本提取 + 递归字符切分器"
```

---

### Task 8: Spec 03 — EmbeddingClient

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/EmbeddingClient.java`

- [ ] **Step 1: 创建 EmbeddingClient**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class EmbeddingClient {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public List<float[]> embed(List<String> texts, ModelConfigEntity model) {
        String apiKey = decryptApiKey(model.getApiKeyEncrypted());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getName());
        body.put("input", texts);

        Request request = new Request.Builder()
                .url(model.getBaseUrl() + "/v1/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Embedding API error: " + response.code() + " " + response.message());
            }
            String respBody = response.body().string();
            Map<String, Object> result = gson.fromJson(respBody, new TypeToken<Map<String, Object>>(){}.getType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            data.sort(Comparator.comparingInt(o -> ((Double) o.get("index")).intValue()));

            List<float[]> embeddings = new ArrayList<>();
            for (Map<String, Object> item : data) {
                @SuppressWarnings("unchecked")
                List<Double> emb = (List<Double>) item.get("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) vec[i] = emb.get(i).floatValue();
                embeddings.add(vec);
            }
            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private String decryptApiKey(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            var encryptCfg = cfg.getEncrypt().getRestful().getAes();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(encryptCfg.getKey().getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(encryptCfg.getIv().getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败，将明文使用: {}", e.getMessage());
            return encrypted;
        }
    }
}
```

> **注：** 项目中已有 `AESUtil` 工具类，解密逻辑应优先复用，此处展示完整实现以避免 plan 中的占位符。

- [ ] **Step 2: 编译验证**

```bash
mvn compile
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/EmbeddingClient.java
git commit -m "feat: EmbeddingClient 调用 OpenAI 兼容 Embedding API"
```

---

### Task 9: Spec 03 — ChromaClient

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/ChromaClient.java`

- [ ] **Step 1: 创建 ChromaClient**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ChromaClient implements InitializingBean {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private String baseUrl;

    @Override
    public void afterPropertiesSet() {
        var chromaCfg = cfg.getKnowledge().getChroma();
        if ("embedded".equals(chromaCfg.getMode())) {
            startEmbeddedChroma();
            this.baseUrl = "http://localhost:" + chromaCfg.getPort();
        } else {
            this.baseUrl = chromaCfg.getBaseUrl();
        }
        log.info("ChromaClient 初始化完成: baseUrl={}", baseUrl);
    }

    private void startEmbeddedChroma() {
        var chromaCfg = cfg.getKnowledge().getChroma();
        try {
            new ProcessBuilder("chroma", "run", "--path", chromaCfg.getDataDir(),
                    "--port", String.valueOf(chromaCfg.getPort()))
                    .inheritIO()
                    .start();
            // 等 Chroma 启动
            Thread.sleep(2000);
            log.info("Embedded Chroma 已启动, path={}, port={}", chromaCfg.getDataDir(), chromaCfg.getPort());
        } catch (Exception e) {
            log.warn("ChromA 子进程启动失败，请确保已安装: pip install chromadb。将尝试连接已有实例。");
        }
    }

    public void createCollection(String collectionName) {
        Map<String, Object> body = Map.of("name", collectionName);
        try {
            post("/api/v1/collections", body);
        } catch (Exception e) {
            log.warn("创建 Chroma collection 失败（可能已存在）: {}", e.getMessage());
        }
    }

    public void deleteCollection(String collectionName) {
        try {
            delete("/api/v1/collections/" + collectionName);
        } catch (Exception e) {
            log.warn("删除 Chroma collection 失败: {}", e.getMessage());
        }
    }

    public void add(String collectionName, List<String> ids, List<float[]> embeddings,
                    List<Map<String, String>> metadatas, List<String> documents) {
        List<List<Float>> embList = new ArrayList<>();
        for (float[] vec : embeddings) {
            List<Float> list = new ArrayList<>();
            for (float v : vec) list.add(v);
            embList.add(list);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embList);
        body.put("metadatas", metadatas);
        body.put("documents", documents);

        post("/api/v1/collections/" + collectionName + "/add", body);
    }

    public List<QueryResult> query(String collectionName, float[] queryEmbedding, int topK) {
        List<Float> qEmb = new ArrayList<>();
        for (float v : queryEmbedding) qEmb.add(v);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(qEmb));
        body.put("n_results", topK);

        String resp = post("/api/v1/collections/" + collectionName + "/query", body);
        Map<String, Object> result = gson.fromJson(resp, new TypeToken<Map<String, Object>>(){}.getType());

        List<QueryResult> results = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<List<String>> idsList = (List<List<String>>) result.get("ids");
        @SuppressWarnings("unchecked")
        List<List<String>> docsList = (List<List<String>>) result.get("documents");
        @SuppressWarnings("unchecked")
        List<List<Double>> distsList = (List<List<Double>>) result.get("distances");
        @SuppressWarnings("unchecked")
        List<List<Map<String, String>>> metasList = (List<List<Map<String, String>>>) result.get("metadatas");

        if (idsList != null && !idsList.isEmpty()) {
            List<String> ids = idsList.get(0);
            List<String> docs = docsList != null && !docsList.isEmpty() ? docsList.get(0) : new ArrayList<>();
            List<Double> dists = distsList != null && !distsList.isEmpty() ? distsList.get(0) : new ArrayList<>();
            List<Map<String, String>> metas = metasList != null && !metasList.isEmpty() ? metasList.get(0) : new ArrayList<>();

            for (int i = 0; i < ids.size(); i++) {
                QueryResult qr = new QueryResult();
                qr.setId(ids.get(i));
                qr.setDocument(i < docs.size() ? docs.get(i) : "");
                qr.setMetadata(i < metas.size() ? metas.get(i) : Map.of());
                qr.setScore(i < dists.size() ? dists.get(i) : 0.0);
                results.add(qr);
            }
        }
        return results;
    }

    private String post(String path, Map<String, Object> body) {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Chroma API error: " + response.code() + " " + errBody);
            }
            return response.body() != null ? response.body().string() : "{}";
        } catch (IOException e) {
            throw new RuntimeException("Chroma 请求失败: " + e.getMessage(), e);
        }
    }

    private String delete(String path) {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + path).delete().build()).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        } catch (IOException e) {
            throw new RuntimeException("Chroma 删除失败: " + e.getMessage(), e);
        }
    }

    @Data
    public static class QueryResult {
        private String id;
        private String document;
        private Map<String, String> metadata;
        private double score;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/ChromaClient.java
git commit -m "feat: ChromaClient HTTP 封装（collection CRUD + add/query）"
```

---

### Task 10: Spec 03 — 文档上传 + 异步处理流水线

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

- [ ] **Step 1: KnowledgeService 加方法**

在 `KnowledgeService.java` 加：
```java
String uploadDoc(String kbId, MultipartFile file, String userName);
void reParseDoc(String docId, String kbId, String userName);
void search(String query, List<String> kbIds, int topK); // 返回 SearchResult 列表
```

- [ ] **Step 2: KnowledgeServiceImpl 实现 uploadDoc**

```java
@Resource
private com.github.hbq969.ai.zephyr.knowledge.pipeline.TikaParser tikaParser;

@Resource
private com.github.hbq969.ai.zephyr.knowledge.pipeline.EmbeddingClient embeddingClient;

@Resource
private com.github.hbq969.ai.zephyr.knowledge.pipeline.ChromaClient chromaClient;

@Override
public String uploadDoc(String kbId, MultipartFile file, String userName) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null) throw new RuntimeException("知识库不存在");

    String docId = UUID.randomUUID().toString();
    // 保存原始文件
    Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
    try {
        Files.createDirectories(dataDir);
        file.transferTo(dataDir.resolve(docId + "_" + file.getOriginalFilename()));
    } catch (IOException e) {
        throw new RuntimeException("保存文件失败", e);
    }

    // 插入文档记录
    KnowledgeDocEntity doc = new KnowledgeDocEntity();
    doc.setId(docId);
    doc.setKbId(kbId);
    doc.setFileName(file.getOriginalFilename());
    doc.setFileType(fileType(file.getOriginalFilename()));
    doc.setFileSize(file.getSize());
    doc.setStatus("processing");
    doc.setChunkCount(0);
    doc.setCreatedAt(System.currentTimeMillis() / 1000);
    knowledgeDao.insertDoc(doc);

    // 异步处理
    processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + file.getOriginalFilename()));
    return docId;
}

@Async
private void processDocAsync(String docId, String kbId, Path filePath) {
    try (InputStream in = Files.newInputStream(filePath)) {
        String text = tikaParser.parse(in, filePath.getFileName().toString());
        TextSplitter splitter = new TextSplitter(800, 150);
        List<String> chunks = splitter.split(text);

        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        var embedModel = modelConfigDao.queryById(kb.getEmbedModelId());
        if (embedModel == null) {
            knowledgeDao.updateDocStatus(docId, "error", 0, "Embedding 模型未配置");
            return;
        }

        String collection = "kb_" + kbId;
        chromaClient.createCollection(collection);

        List<String> ids = new ArrayList<>();
        List<Map<String, String>> metadatas = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ids.add(docId + "_" + i);
            Map<String, String> meta = new HashMap<>();
            meta.put("doc_id", docId);
            meta.put("file_name", filePath.getFileName().toString().replace(docId + "_", ""));
            meta.put("chunk_index", String.valueOf(i));
            metadatas.add(meta);
        }

        List<float[]> embeddings = embeddingClient.embed(chunks, embedModel);
        chromaClient.add(collection, ids, embeddings, metadatas, chunks);

        knowledgeDao.updateDocStatus(docId, "ready", chunks.size(), null);
    } catch (Exception e) {
        log.error("文档处理失败: docId={}", docId, e);
        knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
    }
}

private String fileType(String fileName) {
    if (fileName == null) return "unknown";
    int i = fileName.lastIndexOf('.');
    return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "unknown";
}
```

> **注：** `@Async` 需在 Spring Boot 配置类或主类加 `@EnableAsync`。

- [ ] **Step 3: KnowledgeCtrl 加上传接口**

在 `KnowledgeCtrl.java` 加：
```java
@Operation(summary = "上传文档")
@RequestMapping(path = "/doc/upload", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_upload", apiDesc = "知识库管理_上传文档")
public ReturnMessage<?> uploadDoc(@RequestParam("file") MultipartFile file, @RequestParam String kbId) {
    return ReturnMessage.success(Map.of("docId", knowledgeService.uploadDoc(kbId, file, userName())));
}

@Operation(summary = "重新解析文档")
@RequestMapping(path = "/doc/re-parse", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_reparse", apiDesc = "知识库管理_重新解析")
public ReturnMessage<?> reParseDoc(@RequestBody Map<String, String> body) {
    knowledgeService.reParseDoc(body.get("id"), body.get("kbId"), userName());
    return ReturnMessage.success("ok");
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/
git commit -m "feat: 文档上传 + 异步处理流水线（Tika → 切分 → Embedding → Chroma）"
```

---

### Task 11: Spec 04 — search_knowledge 工具 + ContextBuilder 集成

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: KnowledgeService 加 search 方法**

在 `KnowledgeService.java` 加返回类型和 `SearchResult` 模型：
```java
List<KnowledgeService.SearchResult> search(String query, List<String> kbIds, int topK);

@Data
class SearchResult {
    private final String content;
    private final String sourceFile;
    private final double score;
}
```

在 `KnowledgeServiceImpl` 实现：
```java
@Override
public List<SearchResult> search(String query, List<String> kbIds, int topK) {
    if (kbIds == null || kbIds.isEmpty()) return List.of();

    ModelConfigEntity embedModel = modelConfigDao.queryDefaultByType("embedding");
    if (embedModel == null) throw new RuntimeException("未配置默认 Embedding 模型");

    List<float[]> embeddings = embeddingClient.embed(List.of(query), embedModel);
    if (embeddings.isEmpty()) return List.of();

    List<ChromaClient.QueryResult> allResults = new ArrayList<>();
    for (String kbId : kbIds) {
        try {
            allResults.addAll(chromaClient.query("kb_" + kbId, embeddings.get(0), topK));
        } catch (Exception e) {
            log.warn("知识库 {} 检索失败: {}", kbId, e.getMessage());
        }
    }

    return allResults.stream()
            .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
            .limit(topK)
            .map(r -> new SearchResult(r.getDocument(),
                    r.getMetadata() != null ? r.getMetadata().getOrDefault("file_name", "") : "",
                    r.getScore()))
            .toList();
}
```

- [ ] **Step 2: ContextBuilder 注入工具**

在 `ContextBuilder.build()` 中，`buildUseMemoryTool()` 后加：
```java
// 加载对话勾选的知识库
if (conversationId != null && !conversationId.isEmpty()) {
    List<String> enabledKbIds = knowledgeDao.queryKbIdsByConversation(conversationId);
    if (!enabledKbIds.isEmpty()) {
        List<KnowledgeBaseEntity> kbs = knowledgeDao.queryKbByIds(enabledKbIds);
        systemPrompt.append("\n\n## 已启用知识库\n");
        for (KnowledgeBaseEntity kb : kbs) {
            systemPrompt.append("- ").append(kb.getName()).append(": ")
                .append(kb.getDescription() != null ? kb.getDescription() : "").append("\n");
        }
        systemPrompt.append("使用 search_knowledge 工具检索知识库内容");
    }
}
toolDefs.add(buildSearchKnowledgeTool());
```

加 `buildSearchKnowledgeTool()` 方法和注入 `KnowledgeDao`、`KnowledgeService`：
```java
@Resource
private KnowledgeDao knowledgeDao;

@Resource
private com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService knowledgeService;

private ToolDef buildSearchKnowledgeTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("query", Map.of("type", "string", "description", "检索关键词或问题"));
    props.put("top_k", Map.of("type", "integer", "description", "返回结果数量，默认 5"));

    return ToolDef.builder()
            .type("function")
            .function(ToolDef.FunctionDef.builder()
                    .name("search_knowledge")
                    .description("从已勾选的知识库中检索相关文档片段")
                    .parameters(Map.of("type", "object", "properties", props, "required", List.of("query")))
                    .build())
            .build();
}
```

- [ ] **Step 3: ChatServiceImpl dispatchTools 加分支**

在 `dispatchTools` 的 switch 中加：
```java
case "search_knowledge" -> executeSearchKnowledge(tc.getArguments(), enabledKbIds);
```

加方法：
```java
private String executeSearchKnowledge(Map<String, Object> args, List<String> enabledKbIds) {
    if (enabledKbIds == null || enabledKbIds.isEmpty()) {
        return "{\"message\": \"当前对话未启用任何知识库\"}";
    }
    try {
        String query = args.get("query").toString();
        int topK = 5;
        if (args.containsKey("top_k")) {
            topK = ((Number) args.get("top_k")).intValue();
        }
        List<KnowledgeService.SearchResult> results = knowledgeService.search(query, enabledKbIds, topK);
        if (results.isEmpty()) {
            return "{\"results\": [], \"message\": \"未找到相关文档片段\"}";
        }
        StringBuilder sb = new StringBuilder("{\"results\": [");
        for (int i = 0; i < results.size(); i++) {
            KnowledgeService.SearchResult r = results.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"content\": \"").append(escapeJson(r.getContent())).append("\",")
              .append("\"source\": \"").append(escapeJson(r.getSourceFile())).append("\",")
              .append("\"score\": ").append(String.format("%.4f", r.getScore())).append("}");
        }
        sb.append("]}");
        return sb.toString();
    } catch (Exception e) {
        return "{\"error\": \"知识库检索暂时不可用，请稍后重试: " + escapeJson(e.getMessage()) + "\"}";
    }
}

private String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
}
```

`enabledKbIds` 需要从调用链传下来。在 `dispatchTools` 签名中加参数：
```java
private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls, String userName, List<String> enabledKbIds)
```

调用处传递 `enabledKbIds`，`enabledKbIds` 在调用方通过 `chatDao.queryEnabledKnowledgeBases(conversationId)` 获取。

- [ ] **Step 4: 编译验证**

```bash
mvn compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/ \
  src/main/java/com/github/hbq969/ai/zephyr/chat/
git commit -m "feat: search_knowledge 工具 + ContextBuilder 知识库上下文注入"
```

---

### Task 12: Spec 05 — 前端知识库管理页

**Files:**
- Create: `src/main/resources/static/src/views/settings/KnowledgeSettings.vue`
- Create: `src/main/resources/static/src/views/settings/KnowledgeDocs.vue`
- Modify: `src/main/resources/static/src/router/index.ts`
- Modify: `src/main/resources/static/src/i18n/locale.ts`

- [ ] **Step 1: 路由注册**

在 `router/index.ts` 加：
```typescript
{ path: '/settings/knowledge', name: 'KnowledgeSettings',
  component: () => import('../views/settings/KnowledgeSettings.vue') },
{ path: '/settings/knowledge/:kbId/docs', name: 'KnowledgeDocs',
  component: () => import('../views/settings/KnowledgeDocs.vue') },
```

- [ ] **Step 2: i18n 加 key**

在 `locale.ts` 三语模块各加 `knowledgeMgmt_*` 系列 key：`title`、`createKb`、`editKb`、`uploadDoc`、`reParse`、`docCount`、`embedModel`、`noKb`、`noKbHint`、`noDoc`、`processing`、`ready`、`error` 等。遵循现有三语模式（参考 memoryMgmt 段）。

- [ ] **Step 3: 创建 KnowledgeSettings.vue**

参考 `MemorySettings.vue` 的代码结构（script setup + template + scoped style），功能：
- 页面标题"知识库管理" + 返回按钮 + "创建知识库" circle 按钮
- 卡片列表：每张卡片显示名称、描述、Embedding 模型名、文档数、更新时间
- 卡片操作：编辑、删除
- 创建/编辑对话框：名称、描述、Embedding 模型下拉（调 `/model-config/list?modelType=embedding`）
- 点击卡片 → `router.push('/settings/knowledge/' + kb.id + '/docs')`

- [ ] **Step 4: 创建 KnowledgeDocs.vue**

- 页面标题（知识库名称） + 返回按钮 + "上传文档" circle 按钮
- 文档表格：文件名、类型 tag、大小、chunk 数、状态 tag（processing/ready/error 用不同颜色）、上传时间
- 操作列：删除、重新解析
- 上传对话框：el-upload（accept 限制 pdf/md/txt/html/docx），multipart 上传到 `/knowledge/doc/upload`
- 上传后自动刷新列表

- [ ] **Step 5: SettingsPanel 加入口**

在 `SettingsPanel.vue` 加 "知识库" 导航行（参考 Memory/Skill 的 `sp-item` 模式），点击跳 `/settings/knowledge`。

- [ ] **Step 6: 聊天页知识库勾选**

在 SettingsPanel 或聊天页输入框上方加知识库选择区域：
- 加载 `/knowledge/kb/list` → 展示 checkbox 列表
- 勾选/取消时调 `/knowledge/conversation/kb/save` 同步
- 切换对话时调 `/knowledge/conversation/kb/list?conversationId=` 恢复

- [ ] **Step 7: 构建前端验证**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 8: 端到端验证**

启动后端 + 前端，浏览器操作：
1. 创建 Embedding 模型（如 OpenAI text-embedding-3-small）
2. 创建知识库 → 选择 Embedding 模型
3. 上传 Markdown 文档 → 等待状态变为 ready
4. 聊天页勾选知识库 → 发消息 → 验证 search_knowledge 工具调用

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/src/
git commit -m "feat: 知识库前端管理页 + 聊天页知识库勾选"
```

---

### Task 13: Spec 05 — 前端 UI 设计优化

- [ ] **Step 1: 用 frontend-design skill 优化页面视觉**

确保页面符合项目 `DESIGN.md` 设计系统（warm canvas + coral primary + dark code surfaces），暗黑模式适配。

- [ ] **Step 2: 构建验证 + 浏览器确认**

```bash
cd src/main/resources/static && npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/
git commit -m "style: 知识库页面 UI 优化，暗黑模式适配"
```

---

### Task 14: 集成测试 + CHANGELOG

- [ ] **Step 1: 完整端到端验证**

```bash
# 1. 构建前端
cd src/main/resources/static && npm run build && mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/

# 2. 启动后端
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn spring-boot:run -Dspring-boot.run.profiles=me

# 3. 测试接口
# 创建 Embedding 模型
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/model-config/create" \
  -d '{"name":"text-embedding-3-small","baseUrl":"https://api.openai.com/v1","apiKey":"sk-xxx","modelType":"embedding","dimensions":1536}'

# 创建知识库
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/create" \
  -d '{"name":"测试知识库","description":"测试描述","embedModelId":"xxx"}'

# 上传文档
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@test.md" -F "kbId=xxx" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/upload"
```

- [ ] **Step 2: 更新 CHANGELOG**

```bash
git add CHANGELOG.md
git commit -m "docs: 更新 CHANGELOG v1.1.0"
```

- [ ] **Step 3: Tag & Push**

```bash
git tag -a v1.1.0 -m "v1.1.0: 知识库功能"
git push origin master && git push origin v1.1.0
```

---
