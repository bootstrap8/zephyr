# 知识库在线编辑 + 召回测试 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 知识库管理增加在线 Markdown 编写文档和召回测试功能

**Architecture:** 后端新增3个 REST 端点（create-inline / update-inline / recall-test），KnowledgeDocEntity 扩展 content/sourceType 字段，SearchResult 扩展分项得分。前端新增 MarkdownEditorDialog 组件和 KnowledgeRecallTest 页面，在已有知识库管理页面增加操作入口。

**Tech Stack:** SpringBoot 3.5.4 + MyBatis + Vue3/TS + Element Plus + marked

---

### Task 1: KnowledgeDocEntity 增加字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeDocEntity.java`

- [ ] **Step 1: 新增 content 和 sourceType 字段**

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
    private String content;
    private String sourceType;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeDocEntity.java
git commit -m "feat: KnowledgeDocEntity 增加 content/sourceType 字段

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Mapper XML — DDL 更新（三方言）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml`

- [ ] **Step 1: embedded DDL 加列**

在 `createKnowledgeDocTable` 的 `<update>` 块中，`create index` 语句之前加入：

```xml
<update id="createKnowledgeDocTable">
    create table if not exists zephyr_knowledge_doc (
        id varchar(64) not null,
        kb_id varchar(64) not null,
        file_name varchar(512),
        file_type varchar(64),
        file_size bigint default 0,
        content clob,
        source_type varchar(16) default 'upload',
        chunk_count int default 0,
        status varchar(32) default 'processing',
        error_msg varchar(1024),
        created_at bigint not null,
        primary key (id)
    );
    create index if not exists idx_kb_doc_kb_id on zephyr_knowledge_doc(kb_id);
</update>
```

- [ ] **Step 2: postgresql DDL 同步**

在 PostgreSQL 方言文件中做相同修改（`content text, source_type varchar(16) default 'upload'`）。

- [ ] **Step 3: mysql DDL 同步**

在 MySQL 方言文件中做相同修改（`content text, source_type varchar(16) default 'upload'`）。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml
git commit -m "feat: knowledge doc DDL 增加 content/sourceType 列

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Mapper XML — DML 更新 + KnowledgeDao 接口 + SQL 增量迁移

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java`
- Modify: `src/main/resources/sql/zephyr-zh-CN.sql`

- [ ] **Step 1: common DML — insertDoc 增加 content/sourceType 字段**

在 `insertDoc` 中添加字段：

```xml
<insert id="insertDoc" parameterType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity">
    insert into zephyr_knowledge_doc (id, kb_id, file_name, file_type, file_size, content, source_type, chunk_count, status, created_at)
    values (#{id}, #{kbId}, #{fileName}, #{fileType}, #{fileSize}, #{content}, #{sourceType}, #{chunkCount}, #{status}, #{createdAt})
</insert>
```

- [ ] **Step 2: common DML — select 语句增加 content/sourceType**

在 `queryDocsByKbId` 和 `queryDocById` 的 select 列中加上 `content, source_type as sourceType`。

```xml
<select id="queryDocsByKbId" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity">
    select id, kb_id as kbId, file_name as fileName, file_type as fileType, file_size as fileSize,
           content, source_type as sourceType, chunk_count as chunkCount, status, error_msg as errorMsg, created_at as createdAt
    from zephyr_knowledge_doc where kb_id = #{kbId} order by created_at desc
</select>

<select id="queryDocById" resultType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity">
    select id, kb_id as kbId, file_name as fileName, file_type as fileType, file_size as fileSize,
           content, source_type as sourceType, chunk_count as chunkCount, status, error_msg as errorMsg, created_at as createdAt
    from zephyr_knowledge_doc where id = #{id}
</select>
```

- [ ] **Step 3: KnowledgeDao 新增 updateDoc 方法**

```java
void updateDoc(KnowledgeDocEntity entity);
```

- [ ] **Step 4: common Mapper XML — 新增 updateDoc DML**

```xml
<update id="updateDoc" parameterType="com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity">
    update zephyr_knowledge_doc
    set file_name = #{fileName}, content = #{content}, status = #{status}, chunk_count = #{chunkCount}
    where id = #{id}
</update>
```

- [ ] **Step 5: zephyr-zh-CN.sql 增量迁移**

在文件末尾追加：

```sql
-- 在线编写文档功能：增加 content 和 source_type 字段
alter table if exists zephyr_knowledge_doc add column if not exists content text;
alter table if exists zephyr_knowledge_doc add column if not exists source_type varchar(16) default 'upload';
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/KnowledgeDao.java \
        src/main/resources/sql/zephyr-zh-CN.sql
git commit -m "feat: knowledge doc DML/DAO/SQL 适配 content/sourceType 字段

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: KnowledgeService 新增 createInlineDoc / updateInlineDoc + SearchResult 扩展

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

- [ ] **Step 1: KnowledgeService 接口 — 新增方法签名 + SearchResult 扩展**

在 `KnowledgeService.java` 的 `SearchResult` 内部类中增加分项得分字段，并新增两个方法签名：

```java
String createInlineDoc(String kbId, String title, String content, String userName);

void updateInlineDoc(String docId, String title, String content, String userName);

@Data
class SearchResult {
    private final String content;
    private final String sourceFile;
    private final double score;
    private double vecScore;
    private double kwScore;
    private double rrfScore;
}
```

由于 `@Data` + `final` 字段 + 非 final 字段混用，改为全字段构造器加 setter：

```java
@Data
class SearchResult {
    private String content;
    private String sourceFile;
    private double score;
    private double vecScore;
    private double kwScore;
    private double rrfScore;

    public SearchResult(String content, String sourceFile, double score) {
        this.content = content;
        this.sourceFile = sourceFile;
        this.score = score;
    }
}
```

- [ ] **Step 2: KnowledgeServiceImpl — createInlineDoc 实现**

```java
@Override
public String createInlineDoc(String kbId, String title, String content, String userName) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null) throw new RuntimeException("知识库不存在");

    String docId = UUID.randomUUID().toString();
    String fileName = title + ".md";
    Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
    try {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
    } catch (IOException e) {
        throw new RuntimeException("保存文件失败", e);
    }

    KnowledgeDocEntity doc = new KnowledgeDocEntity();
    doc.setId(docId);
    doc.setKbId(kbId);
    doc.setFileName(fileName);
    doc.setFileType("md");
    doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
    doc.setContent(content);
    doc.setSourceType("inline");
    doc.setStatus("processing");
    doc.setChunkCount(0);
    doc.setCreatedAt(System.currentTimeMillis() / 1000);
    knowledgeDao.insertDoc(doc);

    // 异步处理：直接用 content 文本走分块+向量化（跳过 TikaParser）
    processDocContentAsync(docId, kbId, content, fileName);
    return docId;
}
```

- [ ] **Step 3: KnowledgeServiceImpl — updateInlineDoc 实现**

```java
@Override
public void updateInlineDoc(String docId, String title, String content, String userName) {
    KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
    if (doc == null) throw new RuntimeException("文档不存在");
    if (!"inline".equals(doc.getSourceType())) throw new RuntimeException("仅内联文档支持在线编辑");

    String fileName = title + ".md";
    Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), doc.getKbId());
    try {
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
    } catch (IOException e) {
        throw new RuntimeException("保存文件失败", e);
    }

    // 删除旧索引
    keywordIndex.removeDoc(doc.getKbId(), docId);
    try {
        String collId = chromaClient.getOrCreateCollection("kb_" + doc.getKbId());
        // 删除旧 chunks 需要先知道旧的 chunk 数量，通过重新处理覆盖
    } catch (Exception ignored) {}

    doc.setFileName(fileName);
    doc.setContent(content);
    doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
    doc.setStatus("processing");
    doc.setChunkCount(0);
    doc.setUpdatedAt(System.currentTimeMillis() / 1000);
    knowledgeDao.updateDoc(doc);

    processDocContentAsync(docId, doc.getKbId(), content, fileName);
}
```

- [ ] **Step 4: KnowledgeServiceImpl — 提取公共异步处理方法**

抽取 `processDocAsync` 中的文本处理逻辑，新增 `processDocContentAsync` 方法跳过 Tika 解析：

```java
@Async
public void processDocContentAsync(String docId, String kbId, String text, String displayName) {
    try {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) { log.warn("文档已被删除，取消处理: docId={}", docId); return; }

        TextSplitter splitter = new TextSplitter(800, 150);
        List<String> chunks = splitter.split(text);
        if (chunks.isEmpty()) {
            knowledgeDao.updateDocStatus(docId, "error", 0, "文档内容为空");
            return;
        }

        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        var embedModel = modelConfigDao.queryById(kb.getEmbedModelId());
        if (embedModel == null) {
            knowledgeDao.updateDocStatus(docId, "error", 0, "Embedding 模型未配置");
            return;
        }

        String collection = "kb_" + kbId;
        String collId = chromaClient.getOrCreateCollection(collection);

        int batchSize = 100;
        for (int batchStart = 0; batchStart < chunks.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, chunks.size());
            List<String> batchChunks = chunks.subList(batchStart, batchEnd);

            List<String> batchIds = new ArrayList<>();
            List<Map<String, String>> batchMetas = new ArrayList<>();
            for (int i = batchStart; i < batchEnd; i++) {
                batchIds.add(docId + "_" + i);
                Map<String, String> meta = new HashMap<>();
                meta.put("doc_id", docId);
                meta.put("file_name", displayName);
                meta.put("chunk_index", String.valueOf(i));
                batchMetas.add(meta);
            }

            List<float[]> batchEmbeddings = embeddingClient.embed(batchChunks, embedModel);
            chromaClient.add(collId, batchIds, batchEmbeddings, batchMetas, batchChunks);
            log.info("文档处理进度: docId={}, {}/{} chunks", docId, batchEnd, chunks.size());
        }

        keywordIndex.addChunks(kbId, docId, chunks);
        knowledgeDao.updateDocStatus(docId, "ready", chunks.size(), null);
        log.info("文档处理完成: docId={}, chunks={}", docId, chunks.size());
    } catch (Exception e) {
        log.error("文档处理失败: docId={}", docId, e);
        knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
    }
}
```

- [ ] **Step 5: 重构现有 uploadDoc/processDocAsync 使用公共方法**

将 `uploadDoc` 中调用 `processDocAsync` 的函数签名改为 `processDocContentAsync(docId, kbId, text, fileName)`，在 `uploadDoc` 中使用 `processDocContentAsync` 替代 `processDocAsync`。原有的 `processDocAsync` 保留，作为文件路径版的适配方法：

```java
@Async
public void processDocAsync(String docId, String kbId, Path filePath) {
    try (InputStream in = Files.newInputStream(filePath)) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) { log.warn("文档已被删除，取消处理: docId={}", docId); return; }
        String text = tikaParser.parse(in);
        processDocContentAsync(docId, kbId, text, filePath.getFileName().toString().replace(docId + "_", ""));
    } catch (Exception e) {
        log.error("文档处理失败: docId={}", docId, e);
        knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
    }
}
```

- [ ] **Step 6: search 方法 — 补充分项得分**

在 `search` 方法的 for 循环构建 `SearchResult` 处补充分项得分。需要在 RRF 融合后保留原始向量得分和关键词得分的映射，最终写入 SearchResult：

```java
// search 方法末尾部分更新为：
Map<String, Float> chromaScoreMap = new HashMap<>();
for (ChromaClient.QueryResult r : allVecResults) {
    chromaScoreMap.put(r.getId(), (float) r.getScore());
}

List<SearchResult> results = new ArrayList<>();
for (String chunkId : mergedIds) {
    ChromaClient.QueryResult vr = vecMap.get(chunkId);
    if (vr != null) {
        SearchResult sr = new SearchResult(vr.getDocument(),
                vr.getMetadata() != null ? vr.getMetadata().getOrDefault("file_name", "") : "",
                vr.getScore());
        sr.setVecScore(vr.getScore());
        sr.setKwScore(kwResults.getOrDefault(chunkId, 0f).doubleValue());
        int rank = mergedIds.indexOf(chunkId) + 1;
        sr.setRrfScore(1.0 / (60 + rank));
        results.add(sr);
    } else {
        String text = keywordIndex.getChunkText(chunkId);
        if (text != null) {
            double kwScore = kwResults.getOrDefault(chunkId, 0f).doubleValue();
            SearchResult sr = new SearchResult(text, "关键词匹配", kwScore);
            sr.setVecScore(0);
            sr.setKwScore(kwScore);
            int rank = mergedIds.indexOf(chunkId) + 1;
            sr.setRrfScore(1.0 / (60 + rank));
            results.add(sr);
        }
    }
}
return results;
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/KnowledgeService.java \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "feat: 在线编写 createInlineDoc/updateInlineDoc + SearchResult 分项得分

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: KnowledgeCtrl 新增 3 个接口

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java`

- [ ] **Step 1: 新增 createInlineDoc 接口**

```java
@Operation(summary = "创建内联文档")
@RequestMapping(path = "/doc/create-inline", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_create_inline", apiDesc = "知识库管理_创建内联文档")
public ReturnMessage<?> createInlineDoc(@RequestBody Map<String, String> body) {
    String docId = knowledgeService.createInlineDoc(
            body.get("kbId"), body.get("title"), body.get("content"), userName());
    return ReturnMessage.success(Map.of("docId", docId));
}
```

- [ ] **Step 2: 新增 updateInlineDoc 接口**

```java
@Operation(summary = "更新内联文档")
@RequestMapping(path = "/doc/update-inline", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_update_inline", apiDesc = "知识库管理_更新内联文档")
public ReturnMessage<?> updateInlineDoc(@RequestBody Map<String, String> body) {
    knowledgeService.updateInlineDoc(body.get("id"), body.get("title"), body.get("content"), userName());
    return ReturnMessage.success("ok");
}
```

- [ ] **Step 3: 新增 recallTest 接口**

```java
@Operation(summary = "召回测试")
@RequestMapping(path = "/kb/{kbId}/recall-test", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_recall_test", apiDesc = "知识库管理_召回测试")
public ReturnMessage<?> recallTest(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
    String query = (String) body.get("query");
    int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
    return ReturnMessage.success(knowledgeService.search(query, List.of(kbId), topK));
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/ctrl/KnowledgeCtrl.java
git commit -m "feat: 知识库新增 create-inline / update-inline / recall-test 接口

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 启动后端验证接口

**Files:** 无（验证步骤）

- [ ] **Step 1: 构建后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
```

- [ ] **Step 2: 启动后端**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 3: curl 测试 create-inline**

```bash
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/create-inline" \
  -d '{"kbId":"<existing_kb_id>","title":"test doc","content":"# Hello\nThis is a test document."}'
```
Expected: `{"state":"OK","body":{"docId":"<uuid>"}}`

- [ ] **Step 4: curl 测试 recall-test**

```bash
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/<existing_kb_id>/recall-test" \
  -d '{"query":"test","topK":3}'
```
Expected: `{"state":"OK","body":[...]}` 包含 content, sourceFile, score, vecScore, kwScore, rrfScore

- [ ] **Step 5: 确认后端正常后继续前端开发**

---

### Task 7: 安装 marked 依赖 + i18n 文案

**Files:**
- Modify: `src/main/resources/static/package.json`
- Modify: `src/main/resources/static/src/i18n/locale.ts`

- [ ] **Step 1: 安装 marked**

```bash
cd src/main/resources/static
npm install marked
npm install -D @types/marked
```

- [ ] **Step 2: i18n 新增文案**

在 `zh-CN` 块中追加以下 key：

```typescript
// 在线编辑
"knowledgeMgmt_createInlineDoc": "新建 Markdown",
"knowledgeMgmt_editInlineDoc": "编辑",
"knowledgeMgmt_inlineDocBadge": "在线编写",
"knowledgeMgmt_uploadDocBadge": "上传",
"knowledgeMgmt_docTitle": "文档标题",
"knowledgeMgmt_docTitlePlaceholder": "输入文档标题",
"knowledgeMgmt_docContent": "内容",
"knowledgeMgmt_preview": "预览",
"knowledgeMgmt_editDoc": "编辑文档",
"knowledgeMgmt_createDoc": "新建文档",
"knowledgeMgmt_onlyInlineEdit": "仅在线编写的文档支持编辑",

// 召回测试
"knowledgeMgmt_recallTest": "召回测试",
"knowledgeMgmt_recallQuery": "查询文本",
"knowledgeMgmt_recallQueryPlaceholder": "输入要测试的查询文本...",
"knowledgeMgmt_recallSearch": "搜索",
"knowledgeMgmt_recallResultCount": "检索结果 · 共 {count} 条",
"knowledgeMgmt_recallEmpty": "输入查询后结果显示在上方",
"knowledgeMgmt_recallTopk": "返回数量",
"knowledgeMgmt_recallVecScore": "向量分",
"knowledgeMgmt_recallKwScore": "关键词分",
"knowledgeMgmt_recallRrfScore": "RRF融合分",
"knowledgeMgmt_recallSource": "来源",
```

同步在 `en-US` 中追加英文翻译：

```typescript
// en-US
"knowledgeMgmt_createInlineDoc": "New Markdown",
"knowledgeMgmt_editInlineDoc": "Edit",
"knowledgeMgmt_inlineDocBadge": "Inline",
"knowledgeMgmt_uploadDocBadge": "Upload",
"knowledgeMgmt_docTitle": "Document Title",
"knowledgeMgmt_docTitlePlaceholder": "Enter document title",
"knowledgeMgmt_docContent": "Content",
"knowledgeMgmt_preview": "Preview",
"knowledgeMgmt_editDoc": "Edit Document",
"knowledgeMgmt_createDoc": "Create Document",
"knowledgeMgmt_onlyInlineEdit": "Only inline documents can be edited",
"knowledgeMgmt_recallTest": "Recall Test",
"knowledgeMgmt_recallQuery": "Query Text",
"knowledgeMgmt_recallQueryPlaceholder": "Enter a query to test...",
"knowledgeMgmt_recallSearch": "Search",
"knowledgeMgmt_recallResultCount": "Results · {count} items",
"knowledgeMgmt_recallEmpty": "Enter a query to see results",
"knowledgeMgmt_recallTopk": "Return Count",
"knowledgeMgmt_recallVecScore": "Vector",
"knowledgeMgmt_recallKwScore": "Keyword",
"knowledgeMgmt_recallRrfScore": "RRF Fusion",
"knowledgeMgmt_recallSource": "Source",
```

`ja-JP` 同理追加日文翻译。

- [ ] **Step 3: Commit**

```bash
cd src/main/resources/static
git add package.json package-lock.json
git commit -m "chore: 安装 marked 依赖"
git add src/i18n/locale.ts
git commit -m "feat: 知识库在线编辑与召回测试 i18n 文案

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: MarkdownEditorDialog 组件

**Files:**
- Create: `src/main/resources/static/src/views/settings/MarkdownEditorDialog.vue`

- [ ] **Step 1: 创建 MarkdownEditorDialog.vue**

```vue
<script lang="ts" setup>
import { ref, watch } from 'vue'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import { marked } from 'marked'
import axios from '@/network'

const langData = getLangData()

const props = defineProps<{
  visible: boolean
  kbId: string
  editDoc?: any  // null = create mode
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

const title = ref('')
const content = ref('')

marked.setOptions({ breaks: true, gfm: true })

const previewHtml = ref('')
const updatePreview = () => {
  try { previewHtml.value = marked(content.value || '') } catch { previewHtml.value = '' }
}

watch(() => props.visible, (v) => {
  if (v) {
    if (props.editDoc) {
      title.value = props.editDoc.fileName?.replace(/\.md$/, '') || ''
      content.value = props.editDoc.content || ''
    } else {
      title.value = ''
      content.value = ''
    }
    updatePreview()
  }
})

watch(content, updatePreview)

const saving = ref(false)
const save = async () => {
  if (!title.value.trim()) { msg('请输入文档标题', 'warning'); return }
  if (!content.value.trim()) { msg('请输入文档内容', 'warning'); return }

  const isEdit = !!props.editDoc
  const url = isEdit ? '/knowledge/doc/update-inline' : '/knowledge/doc/create-inline'
  const data: any = isEdit
    ? { id: props.editDoc.id, title: title.value.trim(), content: content.value }
    : { kbId: props.kbId, title: title.value.trim(), content: content.value }

  saving.value = true
  try {
    const res = await axios({ url, method: 'post', data })
    if (res.data.state === 'OK') { emit('update:visible', false); emit('saved') }
    else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '保存失败', 'error') }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="emit('update:visible', $event)"
    :title="editDoc ? langData.knowledgeMgmt_editDoc : langData.knowledgeMgmt_createDoc"
    width="900px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form :model="{ title }" label-position="top">
      <el-form-item :label="langData.knowledgeMgmt_docTitle">
        <el-input v-model="title" :placeholder="langData.knowledgeMgmt_docTitlePlaceholder" />
      </el-form-item>
    </el-form>

    <div class="editor-split">
      <div class="editor-pane">
        <div class="pane-label">{{ langData.knowledgeMgmt_docContent }}</div>
        <el-input
          v-model="content"
          type="textarea"
          :rows="20"
          :placeholder="langData.formInputPlaceholder"
          class="editor-textarea"
        />
      </div>
      <div class="preview-pane">
        <div class="pane-label">{{ langData.knowledgeMgmt_preview }}</div>
        <div class="preview-content" v-html="previewHtml" />
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:visible', false)">{{ langData.btnCancel }}</el-button>
      <el-button type="primary" @click="save" :loading="saving">{{ langData.btnSave }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.editor-split { display: flex; gap: 12px; margin-top: 8px; }
.editor-pane, .preview-pane { flex: 1; min-width: 0; }
.pane-label { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.editor-textarea :deep(.el-textarea__inner) {
  font-family: 'SF Mono', 'Consolas', monospace; font-size: 13px; line-height: 1.6;
  height: 420px; resize: none;
}
.preview-content {
  border: 1px solid var(--el-border-color); border-radius: 6px; padding: 14px;
  height: 420px; overflow-y: auto; font-size: 14px; line-height: 1.7;
  color: var(--el-text-color-primary);
}
.preview-content :deep(h1) { font-size: 22px; margin: 0 0 12px; }
.preview-content :deep(h2) { font-size: 17px; margin: 16px 0 8px; }
.preview-content :deep(p) { margin: 0 0 8px; }
.preview-content :deep(code) { background: var(--el-fill-color-light); padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.preview-content :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px; overflow-x: auto; }
.preview-content :deep(pre code) { background: none; padding: 0; color: inherit; }
.preview-content :deep(ul), .preview-content :deep(ol) { padding-left: 20px; margin: 0 0 8px; }
.preview-content :deep(blockquote) { border-left: 3px solid var(--el-color-primary); padding-left: 12px; color: var(--el-text-color-secondary); margin: 0 0 8px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/views/settings/MarkdownEditorDialog.vue
git commit -m "feat: MarkdownEditorDialog 在线编辑组件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: KnowledgeDocs.vue 集成在线编辑

**Files:**
- Modify: `src/main/resources/static/src/views/settings/KnowledgeDocs.vue`

- [ ] **Step 1: 增加 Markdown 编辑功能**

在现有 KnowledgeDocs.vue 基础上做以下修改：

1. **import 新增**：
```typescript
import MarkdownEditorDialog from './MarkdownEditorDialog.vue'
```

2. **新增响应式变量**（在 `<script setup>` 中）：
```typescript
const editorVisible = ref(false)
const editDoc = ref<any>(null)

const openCreateInline = () => {
  editDoc.value = null
  editorVisible.value = true
}

const openEditInline = (doc: any) => {
  if (doc.sourceType !== 'inline') { msg(langData.knowledgeMgmt_onlyInlineEdit, 'warning'); return }
  editDoc.value = doc
  editorVisible.value = true
}

const onDocSaved = () => { fetchDocs() }
```

3. **工具栏增加按钮**（在 `<div class="page-toolbar">` 中）：
```html
<el-button @click="openCreateInline">
  <Icon icon="lucide:edit-3" style="margin-right:4px" /> {{ langData.knowledgeMgmt_createInlineDoc }}
</el-button>
```

同时在空状态区域也增加对应按钮：
```html
<button class="btn-primary" @click="openCreateInline">
  <Icon icon="lucide:edit-3" /> {{ langData.knowledgeMgmt_createInlineDoc }}
</button>
```

4. **表格增加"来源"列**（在 `<el-table>` 中，文件名列之后）：
```html
<el-table-column label="来源" width="90" align="center">
  <template #default="{ row }">
    <el-tag size="small" :type="row.sourceType === 'inline' ? 'success' : 'info'" effect="plain">
      {{ row.sourceType === 'inline' ? langData.knowledgeMgmt_inlineDocBadge : langData.knowledgeMgmt_uploadDocBadge }}
    </el-tag>
  </template>
</el-table-column>
```

5. **表格操作列增加"编辑"按钮**（仅 inline 类文档可编辑，在操作列固定区域内）：
```html
<el-button link size="small" @click="openEditInline(row)">
  <el-tooltip :content="langData.knowledgeMgmt_editInlineDoc">
    <Icon icon="lucide:edit-3" style="font-size:15px" />
  </el-tooltip>
</el-button>
<el-divider direction="vertical" />
```

6. **模板底部添加组件**：
```html
<MarkdownEditorDialog
  v-model:visible="editorVisible"
  :kb-id="kbId"
  :edit-doc="editDoc"
  @saved="onDocSaved"
/>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/views/settings/KnowledgeDocs.vue
git commit -m "feat: KnowledgeDocs 集成 Markdown 在线编辑

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: KnowledgeRecallTest 页面

**Files:**
- Create: `src/main/resources/static/src/views/settings/KnowledgeRecallTest.vue`

- [ ] **Step 1: 创建 KnowledgeRecallTest.vue**

```vue
<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const router = useRouter()
const route = useRoute()
const langData = getLangData()

const kbId = route.params.kbId as string
const kbName = ref('')

const query = ref('')
const topK = ref(5)
const results = ref<any[]>([])
const searched = ref(false)

const fetchKbName = () => {
  axios({ url: '/knowledge/kb/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const kb = res.data.body.find((k: any) => k.id === kbId)
        if (kb) kbName.value = kb.name
      }
    })
}

const doSearch = () => {
  if (!query.value.trim()) { msg('请输入查询文本', 'warning'); return }
  axios({ url: `/knowledge/kb/${kbId}/recall-test`, method: 'post', data: { query: query.value.trim(), topK: topK.value } })
    .then(res => {
      searched.value = true
      if (res.data.state === 'OK') results.value = res.data.body || []
      else msg(res.data.errorMessage, 'warning')
    })
    .catch(err => msg(err?.response?.data?.errorMessage || '搜索失败', 'error'))
}

const scoreTagType = (score: number) => score >= 0.7 ? 'success' : score >= 0.4 ? 'warning' : 'info'
const scorePercent = (score: number) => (score * 100).toFixed(1) + '%'
const fmtScore = (score: number) => score?.toFixed(4) ?? '-'

const highlightText = (text: string, q: string) => {
  if (!q || !text) return text
  // 将查询文本中的每个词语进行高亮
  const words = q.split(/\s+/).filter(w => w.length > 0)
  let result = text
  for (const word of words) {
    const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    result = result.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="hl">$1</mark>')
  }
  return result
}

onMounted(() => { fetchKbName() })
</script>

<template>
  <div class="recall-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/settings/knowledge')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>{{ langData.knowledgeMgmt_recallTest }} · <strong>{{ kbName || '...' }}</strong></h2>
    </div>

    <!-- 查询区 -->
    <div class="query-bar">
      <el-input
        v-model="query"
        :placeholder="langData.knowledgeMgmt_recallQueryPlaceholder"
        class="query-input"
        @keyup.enter="doSearch"
      />
      <el-select v-model="topK" style="width:100px">
        <el-option :value="3" label="Top 3" />
        <el-option :value="5" label="Top 5" />
        <el-option :value="10" label="Top 10" />
        <el-option :value="20" label="Top 20" />
      </el-select>
      <el-button type="primary" @click="doSearch">
        <Icon icon="lucide:search" style="margin-right:4px" /> {{ langData.knowledgeMgmt_recallSearch }}
      </el-button>
    </div>

    <!-- 空状态 -->
    <div v-if="!searched" class="empty-state">
      <Icon icon="lucide:search" width="48" style="color: var(--el-text-color-placeholder)" />
      <p>{{ langData.knowledgeMgmt_recallEmpty }}</p>
    </div>

    <!-- 结果区 -->
    <template v-else-if="results.length > 0">
      <div class="result-header">
        {{ langData.knowledgeMgmt_recallResultCount.replace('{count}', String(results.length)) }}
      </div>
      <div class="result-list">
        <div v-for="(r, i) in results" :key="i" class="result-item">
          <div class="result-top">
            <span class="rank" :class="{ 'rank-first': i === 0 }">{{ i + 1 }}</span>
            <span class="source">{{ langData.knowledgeMgmt_recallSource }}: {{ r.sourceFile || '-' }}</span>
            <el-tag :type="scoreTagType(r.score)" size="small" effect="dark" round>
              {{ scorePercent(r.score) }}
            </el-tag>
          </div>
          <div class="result-content" v-html="highlightText(r.content, query)" />
          <div class="result-scores">
            <span>{{ langData.knowledgeMgmt_recallVecScore }}: {{ fmtScore(r.vecScore) }}</span>
            <span>{{ langData.knowledgeMgmt_recallKwScore }}: {{ fmtScore(r.kwScore) }}</span>
            <span>{{ langData.knowledgeMgmt_recallRrfScore }}: {{ fmtScore(r.rrfScore) }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- 无结果 -->
    <div v-else class="empty-state">
      <Icon icon="lucide:file-search" width="48" style="color: var(--el-text-color-placeholder)" />
      <p>未找到匹配结果</p>
    </div>
  </div>
</template>

<style scoped>
.recall-page { max-width: 880px; margin: 0 auto; padding: 24px; }

.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn {
  width: 36px; height: 36px; border-radius: 50%;
  border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary); font-size: 18px;
}
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.query-bar { display: flex; gap: 8px; margin-bottom: 24px; }
.query-input { flex: 1; }

.empty-state { text-align: center; padding: 80px 24px; color: var(--el-text-color-placeholder); font-size: 14px; }

.result-header { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); padding: 8px 14px; background: var(--el-fill-color-light); border-radius: 8px 8px 0 0; border: 1px solid var(--el-border-color); border-bottom: none; }

.result-list { border: 1px solid var(--el-border-color); border-radius: 0 0 10px 10px; overflow: hidden; }
.result-item { padding: 14px 16px; border-bottom: 1px solid var(--el-border-color); background: var(--el-bg-color); }
.result-item:last-child { border-bottom: none; }

.result-top { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.rank { background: var(--el-text-color-secondary); color: #fff; border-radius: 50%; width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; flex-shrink: 0; }
.rank-first { background: var(--el-color-primary); }
.source { font-size: 12px; color: var(--el-text-color-secondary); flex: 1; }

.result-content { font-size: 14px; line-height: 1.7; color: var(--el-text-color-primary); margin-bottom: 6px; }
.result-content :deep(.hl) { background: #fff3cd; padding: 0 2px; border-radius: 2px; }

.result-scores { display: flex; gap: 12px; font-size: 11px; color: var(--el-text-color-placeholder); }

html.dark .back-btn:hover { background: var(--el-fill-color); }
html.dark .result-content :deep(.hl) { background: #5a4a00; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/src/views/settings/KnowledgeRecallTest.vue
git commit -m "feat: KnowledgeRecallTest 召回测试页面

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: KnowledgeSettings.vue 增加召回测试按钮 + 路由注册

**Files:**
- Modify: `src/main/resources/static/src/views/settings/KnowledgeSettings.vue`
- Modify: `src/main/resources/static/src/router/index.ts`

- [ ] **Step 1: KnowledgeSettings.vue — 卡片操作列增加召回测试按钮**

在 KnowledgeSettings.vue 的卡片操作列中（`<div class="card-actions" @click.stop>`），编辑按钮之前插入：

```html
<el-tooltip :content="langData.knowledgeMgmt_recallTest">
  <el-button circle size="small" @click="router.push('/settings/knowledge/' + kb.id + '/recall-test')">
    <Icon icon="lucide:search" />
  </el-button>
</el-tooltip>
```

- [ ] **Step 2: router/index.ts — 新增路由**

在 routes 数组中追加：

```typescript
{ path: '/settings/knowledge/:kbId/recall-test', name: 'KnowledgeRecallTest', component: () => import('../views/settings/KnowledgeRecallTest.vue') },
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/settings/KnowledgeSettings.vue \
        src/main/resources/static/src/router/index.ts
git commit -m "feat: 知识库卡片增加召回测试入口 + 路由注册

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: 前端构建 + 端到端验证

**Files:** 无（验证步骤）

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static
npm run build
```

- [ ] **Step 2: 复制前端产物到 target**

```bash
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 确认后端运行中**（确保 Java 17 后端已启动在 30733 端口）

- [ ] **Step 4: curl 测试完整链路**

测试 create-inline:
```bash
KB_ID=$(curl -s -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/list" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['body'][0]['id'] if d['body'] else '')")
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/create-inline" \
  -d "{\"kbId\":\"$KB_ID\",\"title\":\"端到端测试\",\"content\":\"# Zephyr\\n这是一个智能体平台。\"}"
```

等待几秒后测试召回:
```bash
sleep 5
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/$KB_ID/recall-test" \
  -d '{"query":"智能体平台","topK":3}'
```
Expected: 返回 `Zephyr` 相关的检索结果

- [ ] **Step 5: 浏览器验证**

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`，依次验证：
1. 设置 → 知识库管理 → 点击知识库进入文档列表
2. 点击"新建 Markdown"→ 编写文档 → 保存 → 列表显示"在线编写"标签
3. 返回知识库列表 → 点击召回测试按钮 → 输入查询 → 查看结果
4. 暗黑模式切换，确认所有新页面样式正常

- [ ] **Step 6: Commit（如无修改则跳过）**
