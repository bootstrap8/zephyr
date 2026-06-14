# 知识库共享机制 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 知识库增加 scope 共享字段，admin 可将知识库设为共享供全员查看/召回测试/对话使用

**Architecture:** 完全参照 MCP 共享模式 — Entity 加 scope 字段、DAO 加共享查询、Service 做权限校验、前端 KnowledgeSettings 加 tabs、InputArea 知识库选择器分共享/个人两栏

**Tech Stack:** Java 17 + SpringBoot 3.5.4 + MyBatis + Vue3 + TS

---

### Task 1: Entity + VO 加 scope 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeBaseEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/model/KnowledgeVO.java`

- [ ] **Step 1: KnowledgeBaseEntity 加 scope**

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
    private String scope = "user";
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: KnowledgeVO 加 scope + canManage**

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
    private String scope;
    private boolean canManage;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeBaseEntity.java \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/model/KnowledgeVO.java
git commit -m "feat: KnowledgeBaseEntity/VO 加 scope 和 canManage 字段"
```

---

### Task 2: KnowledgeDao 加共享查询方法

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java`

- [ ] **Step 1: 添加新方法声明**

在接口末尾添加：

```java
List<KnowledgeBaseEntity> querySharedKbs();
void updateKbScope(@Param("id") String id, @Param("scope") String scope);
```

完整接口变为：

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

    List<KnowledgeBaseEntity> querySharedKbs();
    void updateKbScope(@Param("id") String id, @Param("scope") String scope);

    // Document CRUD
    List<KnowledgeDocEntity> queryDocsByKbId(@Param("kbId") String kbId);
    KnowledgeDocEntity queryDocById(@Param("id") String id);
    void insertDoc(KnowledgeDocEntity entity);
    void updateDoc(KnowledgeDocEntity entity);
    void updateDocStatus(@Param("id") String id, @Param("status") String status,
                         @Param("chunkCount") Integer chunkCount, @Param("errorMsg") String errorMsg);
    void deleteDoc(@Param("id") String id);
    void deleteDocsByKbId(@Param("kbId") String kbId);

    // Conversation-KB association
    List<String> queryKbIdsByConversation(@Param("conversationId") String conversationId);
    void insertConversationKb(@Param("conversationId") String conversationId, @Param("kbId") String kbId);
    void deleteConversationKb(@Param("conversationId") String conversationId);
    void deleteConversationKbByKbId(@Param("kbId") String kbId);
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java
git commit -m "feat: KnowledgeDao 加 querySharedKbs 和 updateKbScope 方法"
```

---

### Task 3: Mapper XML — DDL 三方言加 scope 列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml`

- [ ] **Step 1: embedded DDL 加 scope 列**

在 `createKnowledgeBaseTable` 的 `embed_model_id varchar(36),` 后加一行：

```xml
      scope varchar(16) default 'user',
```

完整片段（仅展示变更行上下文）：

```xml
      embed_model_id varchar(36),
      scope varchar(16) default 'user',
      created_at bigint,
```

- [ ] **Step 2: mysql DDL 同样加 scope 列**

```xml
      embed_model_id varchar(36),
      scope varchar(16) default 'user',
      created_at bigint,
```

- [ ] **Step 3: postgresql DDL 同样加 scope 列**

```xml
      embed_model_id varchar(36),
      scope varchar(16) default 'user',
      created_at bigint,
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml
git commit -m "feat: knowledge DDL 三方言加 scope 列"
```

---

### Task 4: Mapper XML — common DML 加 scope + 新查询

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml`

- [ ] **Step 1: insertKb 加 scope 列**

`insertKb` 的 columns 和 values 各加 `scope,`：

```xml
  <insert id="insertKb">
    insert into zephyr_knowledge_base (id, user_name, name, description, embed_model_id, scope, created_at, updated_at)
    values (#{id}, #{userName}, #{name}, #{description}, #{embedModelId}, #{scope}, #{createdAt}, #{updatedAt})
  </insert>
```

- [ ] **Step 2: queryKbByUserName 和 queryKbById 加 scope**

在 select 子句中加 `scope,`：

`queryKbByUserName`:
```xml
  <select id="queryKbByUserName" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, scope,
      created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base
    where user_name = #{userName}
    order by created_at desc
  </select>
```

`queryKbById`:
```xml
  <select id="queryKbById" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, scope,
      created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base
    where id = #{id}
  </select>
```

`queryKbByIds`:
```xml
  <select id="queryKbByIds" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, scope,
      created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base
    where id in
    <foreach collection="ids" item="item" open="(" separator="," close=")">
      #{item}
    </foreach>
  </select>
```

- [ ] **Step 3: 新增 querySharedKbs**

在 `deleteKb` 后面加：

```xml
  <select id="querySharedKbs" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity">
    select id, user_name as userName, name, description, embed_model_id as embedModelId, scope,
      created_at as createdAt, updated_at as updatedAt
    from zephyr_knowledge_base
    where scope = 'shared'
    order by created_at desc
  </select>
```

- [ ] **Step 4: 新增 updateKbScope**

```xml
  <update id="updateKbScope">
    update zephyr_knowledge_base set scope = #{scope} where id = #{id}
  </update>
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml
git commit -m "feat: knowledge Mapper common DML 加 scope + querySharedKbs/updateKbScope"
```

---

### Task 5: KnowledgeServiceImpl — listKb 合并共享 + 权限校验

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

- [ ] **Step 1: KnowledgeService 接口加 toggleKbScope**

在接口末尾加：

```java
void toggleKbScope(String id, String scope, String userName);
```

- [ ] **Step 2: KnowledgeServiceImpl — 加 import、常量、admin 判断**

在 import 区加：
```java
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import java.util.ArrayList;
```

在字段区加常量和方法：
```java
private static final String SCOPE_SHARED = "shared";
private static final String SCOPE_USER = "user";

private boolean isAdmin() {
    UserInfo ui = UserContext.getNoCheck();
    return ui != null && ui.isAdmin();
}

private void checkSharedManage() {
    if (!isAdmin()) throw new RuntimeException("仅 admin 可管理共享知识库");
}
```

- [ ] **Step 3: 修改 listKb — 合并自己的 + 共享的，设置 canManage**

替换现有 `listKb` 方法：

```java
@Override
public List<KnowledgeVO> listKb(String userName) {
    List<KnowledgeBaseEntity> all = new ArrayList<>();
    all.addAll(knowledgeDao.queryKbByUserName(userName));
    all.addAll(knowledgeDao.querySharedKbs());
    // 按 id 去重
    Map<String, KnowledgeBaseEntity> dedup = new LinkedHashMap<>();
    for (KnowledgeBaseEntity kb : all) {
        dedup.putIfAbsent(kb.getId(), kb);
    }
    boolean admin = isAdmin();
    List<KnowledgeVO> vos = new ArrayList<>();
    for (KnowledgeBaseEntity kb : dedup.values()) {
        KnowledgeVO vo = new KnowledgeVO();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setEmbedModelId(kb.getEmbedModelId());
        vo.setScope(kb.getScope() != null ? kb.getScope() : SCOPE_USER);
        if (kb.getEmbedModelId() != null) {
            var model = modelConfigDao.queryById(kb.getEmbedModelId());
            if (model != null) vo.setEmbedModelName(model.getName());
        }
        List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kb.getId());
        vo.setDocCount(docs.size());
        vo.setCreatedAt(kb.getCreatedAt());
        vo.setUpdatedAt(kb.getUpdatedAt());
        // canManage: 共享 KB 仅 admin 可管理; 自己的 KB admin + 本人可管理
        if (SCOPE_SHARED.equals(vo.getScope())) {
            vo.setCanManage(admin);
        } else {
            vo.setCanManage(admin || kb.getUserName().equals(userName));
        }
        vos.add(vo);
    }
    return vos;
}
```

- [ ] **Step 4: createKb — 支持 scope 参数**

修改 `createKb`：

```java
@Override
public KnowledgeBaseEntity createKb(Map<String, String> body, String userName) {
    String scope = body.getOrDefault("scope", SCOPE_USER);
    if (SCOPE_SHARED.equals(scope)) checkSharedManage();
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.setId(UUID.randomUUID().toString());
    entity.setUserName(userName);
    entity.setName(body.get("name"));
    entity.setDescription(body.getOrDefault("description", ""));
    entity.setEmbedModelId(body.get("embedModelId"));
    entity.setScope(scope);
    long now = System.currentTimeMillis() / 1000;
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    knowledgeDao.insertKb(entity);
    return entity;
}
```

- [ ] **Step 5: updateKb — 共享 KB 权限校验**

在 `updateKb` 开头加校验：

```java
@Override
public void updateKb(Map<String, String> body, String userName) {
    KnowledgeBaseEntity entity = knowledgeDao.queryKbById(body.get("id"));
    if (entity == null) throw new RuntimeException("知识库不存在");
    if (SCOPE_SHARED.equals(entity.getScope())) checkSharedManage();
    entity.setName(body.get("name"));
    entity.setDescription(body.getOrDefault("description", ""));
    entity.setEmbedModelId(body.get("embedModelId"));
    entity.setUpdatedAt(System.currentTimeMillis() / 1000);
    knowledgeDao.updateKb(entity);
}
```

- [ ] **Step 6: deleteKb — 共享 KB 权限校验**

在 `deleteKb` 开头加校验：

```java
@Override
@Transactional
public void deleteKb(String id, String userName) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(id);
    if (kb == null) throw new RuntimeException("知识库不存在");
    if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
    knowledgeDao.deleteConversationKbByKbId(id);
    keywordIndex.removeKb(id);
    knowledgeDao.deleteDocsByKbId(id);
    knowledgeDao.deleteKb(id);
}
```

- [ ] **Step 7: uploadDoc — 共享 KB 仅 admin 可上传**

在现有校验后加 scope 检查：

```java
@Override
public String uploadDoc(String kbId, MultipartFile file, String userName) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null) throw new RuntimeException("知识库不存在");
    if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
    // ... 后续不变
}
```

- [ ] **Step 8: createInlineDoc、updateInlineDoc、deleteDoc — 同样加校验**

`createInlineDoc`:
```java
@Override
public String createInlineDoc(String kbId, String title, String content, String userName) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null) throw new RuntimeException("知识库不存在");
    if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
    // ... 后续不变
}
```

`updateInlineDoc`:
```java
@Override
public void updateInlineDoc(String docId, String title, String content, String userName) {
    KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
    if (doc == null) throw new RuntimeException("文档不存在");
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
    if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
    // ... 后续不变
}
```

`deleteDoc`:
```java
@Override
public void deleteDoc(String id) {
    KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);
    if (doc == null) return;
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
    if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
    if (doc != null) keywordIndex.removeDoc(doc.getKbId(), id);
    knowledgeDao.deleteDoc(id);
}
```

注意：`updateInlineDoc` 和 `deleteDoc` 需要注入 `KnowledgeDao`（已有）。`updateInlineDoc` 已有 `KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);` 查询，在其后加 KB 查询和校验即可。`deleteDoc` 已有 `KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);`，在其后加即可。

- [ ] **Step 9: 新增 toggleKbScope**

```java
@Override
@Transactional
public void toggleKbScope(String id, String scope, String userName) {
    checkSharedManage();
    knowledgeDao.updateKbScope(id, scope);
}
```

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "feat: KnowledgeService 支持共享 — 合并查询 + 权限校验 + scope 切换"
```

---

### Task 6: KnowledgeCtrl — 新增 toggleKbScope 接口

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java`

- [ ] **Step 1: 在文件末尾（最后一个 `}` 前）添加新接口**

```java
    @Operation(summary = "切换知识库共享状态（仅admin）")
    @RequestMapping(path = "/kb/scope/toggle", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_toggleScope", apiDesc = "知识库管理_切换共享状态")
    public ReturnMessage<?> toggleKbScope(@RequestBody Map<String, String> body) {
        knowledgeService.toggleKbScope(body.get("id"), body.get("scope"), userName());
        return ReturnMessage.success("ok");
    }
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java
git commit -m "feat: KnowledgeCtrl 加 /kb/scope/toggle 接口（仅admin）"
```

---

### Task 7: 前端 — types + i18n + store

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`
- Modify: `src/main/resources/static/src/i18n/locale.ts`
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: types/chat.ts — KB 接口加 scope + canManage**

找到 KB 相关的 interface，加字段：

```typescript
export interface KnowledgeBase {
  id?: string
  name: string
  description?: string
  embedModelId?: string
  embedModelName?: string
  docCount?: number
  scope?: 'user' | 'shared'
  canManage?: boolean
  createdAt?: number
  updatedAt?: number
}
```

（注：如果 KB 类型定义不在 chat.ts 而是在其他文件中，请根据实际文件位置修改。）

- [ ] **Step 2: i18n/locale.ts — 加共享相关 key**

在 `knowledgeMgmt_*` 区域末尾加：

```typescript
// 在 knowledgeMgmt_recallTest 后面加
"knowledgeMgmt_sharedTab": "共享知识库",
"knowledgeMgmt_userTab": "我的知识库",
"knowledgeMgmt_shared": "共享",
"knowledgeMgmt_scope": "范围",
"knowledgeMgmt_personal": "个人",
"knowledgeMgmt_shareToAll": "设为共享",
"knowledgeMgmt_unshare": "取消共享",
"knowledgeMgmt_noShared": "暂无共享知识库",
"knowledgeMgmt_noUser": "暂无个人知识库",
```

- [ ] **Step 3: store/settings.ts — 加 toggleKbScope 方法**

在 `loadKnowledgeBases` 附近加：

```typescript
  async function toggleKbScope(id: string, scope: string) {
    await axios({ url: '/knowledge/kb/scope/toggle', method: 'post', data: { id, scope } })
    await loadKnowledgeBases()
  }
```

并在 return 中导出：

```typescript
    toggleKbScope,
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/types/chat.ts \
        src/main/resources/static/src/i18n/locale.ts \
        src/main/resources/static/src/store/settings.ts
git commit -m "feat: 前端 types/i18n/store 支持知识库共享"
```

---

### Task 8: KnowledgeSettings.vue — Tab 切换 + scope 控制

**Files:**
- Modify: `src/main/resources/static/src/views/settings/KnowledgeSettings.vue`

- [ ] **Step 1: script — 加 computed、activeTab、scope 逻辑**

将 `<script>` 中的 import 改为：
```typescript
import { ref, reactive, computed, onMounted } from 'vue'
```

加 computed 分组和状态：
```typescript
const serverScope = ref<'user' | 'shared'>('user')
const activeTab = ref('shared')
const sharedBases = computed(() => store.knowledgeBases.filter((kb: any) => kb.scope === 'shared'))
const userBases = computed(() => store.knowledgeBases.filter((kb: any) => kb.scope !== 'shared'))
```

修改 `openCreate`：
```typescript
const openCreate = () => {
  dialogTitle.value = langData.knowledgeMgmt_createKb
  editingId.value = ''
  form.name = ''; form.description = ''; form.embedModelId = ''
  serverScope.value = 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}
```

修改 `openEdit`：
```typescript
const openEdit = (kb: any) => {
  dialogTitle.value = langData.knowledgeMgmt_editKb
  editingId.value = kb.id
  form.name = kb.name; form.description = kb.description || ''; form.embedModelId = kb.embedModelId || ''
  serverScope.value = kb.scope || 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}
```

修改 `saveKb` 的 data：
```typescript
const data: any = { name: form.name.trim(), description: form.description.trim(), embedModelId: form.embedModelId, scope: serverScope.value }
```

加 `toggleScope` 方法：
```typescript
async function toggleScope(id: string, newScope: string) {
  await store.toggleKbScope(id, newScope)
}
```

- [ ] **Step 2: template — 替换为 tabs 布局**

替换现有 `<div v-if="store.knowledgeBases.length === 0" ...>` 空状态和 `<div v-else class="card-list">` 之间的内容：

```html
    <el-tabs v-if="store.knowledgeBases.length > 0" v-model="activeTab" class="kb-tabs">
      <el-tab-pane :label="(langData.knowledgeMgmt_sharedTab || '共享知识库') + ' (' + sharedBases.length + ')'" name="shared">
        <div v-if="sharedBases.length > 0" class="card-list">
          <div v-for="kb in sharedBases" :key="kb.id" class="kb-card" @click="goDocs(kb.id)">
            <div class="card-inner">
              <div class="card-header">
                <Icon icon="lucide:library" class="card-icon" />
                <div class="card-body">
                  <div class="card-title">{{ kb.name }}</div>
                  <div class="card-desc" v-if="kb.description">{{ kb.description }}</div>
                  <div class="card-meta">
                    <span class="kb-embed-badge">{{ kb.embedModelName || langData.knowledgeMgmt_embedModel }}</span>
                    <span class="badge-scope-shared">{{ langData.knowledgeMgmt_shared || '共享' }}</span>
                    <span>{{ langData.knowledgeMgmt_docCount.replace('{count}', kb.docCount || 0) }}</span>
                    <span>{{ fmtTime(kb.updatedAt) }}</span>
                  </div>
                </div>
                <div class="card-actions" @click.stop>
                  <el-tooltip :content="langData.knowledgeMgmt_recallTest">
                    <el-button circle size="small" @click="router.push('/settings/knowledge/' + kb.id + '/recall-test')">
                      <Icon icon="lucide:search" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="kb.scope === 'shared' ? langData.knowledgeMgmt_unshare : langData.knowledgeMgmt_shareToAll">
                    <el-button circle size="small" @click="toggleScope(kb.id!, kb.scope === 'shared' ? 'user' : 'shared')">
                      <Icon :icon="kb.scope === 'shared' ? 'lucide:lock' : 'lucide:share-2'" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnEdit">
                    <el-button circle size="small" @click="openEdit(kb)">
                      <Icon icon="lucide:edit-3" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnDelete">
                    <el-button circle size="small" @click="deleteKb(kb)">
                      <Icon icon="lucide:trash-2" />
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-result">
          <Icon icon="lucide:inbox" class="empty-icon" />
          <p class="empty-desc">{{ langData.knowledgeMgmt_noShared || '暂无共享知识库' }}</p>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="(langData.knowledgeMgmt_userTab || '我的知识库') + ' (' + userBases.length + ')'" name="user">
        <div v-if="userBases.length > 0" class="card-list">
          <div v-for="kb in userBases" :key="kb.id" class="kb-card" @click="goDocs(kb.id)">
            <div class="card-inner">
              <div class="card-header">
                <Icon icon="lucide:library" class="card-icon" />
                <div class="card-body">
                  <div class="card-title">{{ kb.name }}</div>
                  <div class="card-desc" v-if="kb.description">{{ kb.description }}</div>
                  <div class="card-meta">
                    <span class="kb-embed-badge">{{ kb.embedModelName || langData.knowledgeMgmt_embedModel }}</span>
                    <span>{{ langData.knowledgeMgmt_docCount.replace('{count}', kb.docCount || 0) }}</span>
                    <span>{{ fmtTime(kb.updatedAt) }}</span>
                  </div>
                </div>
                <div class="card-actions" @click.stop>
                  <el-tooltip :content="langData.knowledgeMgmt_recallTest">
                    <el-button circle size="small" @click="router.push('/settings/knowledge/' + kb.id + '/recall-test')">
                      <Icon icon="lucide:search" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.knowledgeMgmt_shareToAll">
                    <el-button circle size="small" @click="toggleScope(kb.id!, 'shared')">
                      <Icon icon="lucide:share-2" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnEdit">
                    <el-button circle size="small" @click="openEdit(kb)">
                      <Icon icon="lucide:edit-3" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnDelete">
                    <el-button circle size="small" @click="deleteKb(kb)">
                      <Icon icon="lucide:trash-2" />
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-result">
          <Icon icon="lucide:inbox" class="empty-icon" />
          <p class="empty-desc">{{ langData.knowledgeMgmt_noUser || '暂无个人知识库' }}</p>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div v-if="store.knowledgeBases.length === 0" class="empty-state">
      <Icon icon="lucide:library" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.knowledgeMgmt_noKb }}</h3>
      <p class="empty-desc">{{ langData.knowledgeMgmt_noKbHint }}</p>
      <button class="btn-primary" @click="openCreate">
        <Icon icon="lucide:plus" /> {{ langData.knowledgeMgmt_createKb }}
      </button>
    </div>
```

注意：卡片点击导航改为 `goDocs(kb.id)`，需要在 script 中加辅助函数：
```typescript
function goDocs(kbId: string) {
  router.push('/settings/knowledge/' + kbId + '/docs')
}
```

- [ ] **Step 3: template — create/edit 弹窗加 scope 切换**

在 `<el-dialog>` 表单项末尾（embedModel 下面）加：

```html
        <el-form-item v-if="store.isAdmin" :label="langData.knowledgeMgmt_scope || '范围'">
          <div class="transport-toggle">
            <button :class="{ active: serverScope === 'user' }" @click="serverScope = 'user'">{{ langData.knowledgeMgmt_personal || '个人' }}</button>
            <button :class="{ active: serverScope === 'shared' }" @click="serverScope = 'shared'">{{ langData.knowledgeMgmt_shared || '共享' }}</button>
          </div>
        </el-form-item>
```

- [ ] **Step 4: style — 加新样式**

在 `<style scoped>` 末尾加：

```css
.kb-tabs { margin-top: 0; }
.badge-scope-shared { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.empty-result { text-align: center; padding: 64px 24px; }
.empty-result .empty-icon { font-size: 40px; color: var(--el-text-color-placeholder); }
.empty-result .empty-desc { font-size: 13px; color: var(--el-text-color-secondary); }

.transport-toggle { display: flex; border: 1px solid var(--el-border-color); border-radius: 6px; overflow: hidden; }
.transport-toggle button { flex: 1; padding: 8px 12px; border: none; background: var(--el-bg-color); color: var(--el-text-color-secondary); font-size: 13px; cursor: pointer; font-family: inherit; transition: background 0.15s; }
.transport-toggle button.active { background: var(--el-color-primary); color: #fff; font-weight: 500; }

/* dark mode */
html.dark .empty-result .empty-icon { color: var(--el-text-color-placeholder); }
html.dark .empty-result .empty-desc { color: var(--el-text-color-secondary); }
```

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/src/views/settings/KnowledgeSettings.vue
git commit -m "feat: KnowledgeSettings Tab 切换共享/个人 + scope 控制"
```

---

### Task 9: InputArea.vue — 知识库选择器分栏

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: script — 加 computed import + 分组 computed**

在 import 行加 `computed`：
```typescript
import {ref, reactive, computed, onMounted} from 'vue'
```

在 `toggleKbList` 函数附近加：
```typescript
const sharedKbs = computed(() => settingsStore.knowledgeBases.filter((kb: any) => kb.scope === 'shared'))
const userKbs = computed(() => settingsStore.knowledgeBases.filter((kb: any) => kb.scope !== 'shared'))
```

- [ ] **Step 2: template — 替换知识库选择下拉面板**

将现有（line 576-587）的 `<div v-if="showKbList" ...>` 内容替换为：

```html
            <div v-if="showKbList" class="pick-dropdown kb-dropdown" @click.stop>
              <div v-if="settingsStore.knowledgeBases.length === 0" class="sub-loading">{{ langData.knowledgeMgmt_noKb }}</div>
              <template v-else>
                <template v-if="sharedKbs.length > 0">
                  <div class="kb-section-label">{{ langData.knowledgeMgmt_sharedTab || '共享知识库' }}</div>
                  <div v-for="kb in sharedKbs" :key="kb.id" class="pick-option kb-option"
                       :class="{ current: selectedKbIds.includes(kb.id) }"
                       @click="toggleKb(kb.id)">
                    <span class="kb-check-box" :class="{ checked: selectedKbIds.includes(kb.id) }">
                      <Icon v-if="selectedKbIds.includes(kb.id)" icon="lucide:check" class="kb-chk-icon" />
                    </span>
                    <span class="kb-opt-name" :title="kb.name">{{ kb.name }}</span>
                    <span class="skill-scope-badge scope-shared">{{ langData.knowledgeMgmt_shared || '共享' }}</span>
                    <span class="kb-opt-count">{{ kb.docCount }} 文档</span>
                  </div>
                </template>
                <div v-if="sharedKbs.length > 0 && userKbs.length > 0" class="kb-section-divider"></div>
                <template v-if="userKbs.length > 0">
                  <div class="kb-section-label">{{ langData.knowledgeMgmt_userTab || '我的知识库' }}</div>
                  <div v-for="kb in userKbs" :key="kb.id" class="pick-option kb-option"
                       :class="{ current: selectedKbIds.includes(kb.id) }"
                       @click="toggleKb(kb.id)">
                    <span class="kb-check-box" :class="{ checked: selectedKbIds.includes(kb.id) }">
                      <Icon v-if="selectedKbIds.includes(kb.id)" icon="lucide:check" class="kb-chk-icon" />
                    </span>
                    <span class="kb-opt-name" :title="kb.name">{{ kb.name }}</span>
                    <span class="skill-scope-badge scope-user">{{ langData.knowledgeMgmt_personal || '个人' }}</span>
                    <span class="kb-opt-count">{{ kb.docCount }} 文档</span>
                  </div>
                </template>
              </template>
            </div>
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: InputArea 知识库选择器分共享/个人两栏展示"
```

---

### Task 10: 后端接口验证

- [ ] **Step 1: 构建后端并启动**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me &
```

- [ ] **Step 2: 验证 listKb 返回 scope + canManage**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/list"
```

预期：返回的每个 KB 对象含 `scope` 和 `canManage` 字段

- [ ] **Step 3: 验证 scope toggle**

```bash
# admin 创建共享知识库
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/create" \
  -d '{"name":"共享测试库","scope":"shared","embedModelId":"<existing-model-id>"}'

# admin 切换 scope
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/scope/toggle" \
  -d '{"id":"<kb-id>","scope":"user"}'
```

预期：成功返回 `{"state":"OK"}`

- [ ] **Step 4: 验证普通用户操作共享 KB 被拒绝**

```bash
# 普通用户尝试更新共享 KB
curl -u normaluser:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/update" \
  -d '{"id":"<shared-kb-id>","name":"hack"}'
```

预期：返回错误 "仅 admin 可管理共享知识库"

- [ ] **Step 5: 构建前端并复制到 target**

```bash
cd src/main/resources/static && npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 6: 浏览器验证**

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`，进入设置 → 知识库管理，验证：
- Tab 切换正常
- 共享/个人 badge 显示正确
- admin 看到 scope 切换、编辑、删除按钮
- 普通用户只看到召回测试按钮
- 输入框知识库选择器分栏展示

- [ ] **Step 7: 提交最终验证通过**

```bash
# 如果有 lint 修复等微调
git add -A && git commit -m "chore: 知识库共享 — 联调修复"
```

（如无修改则跳过）
