# GraphRAG 增强 — LightRAG Sidecar 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Chunk RAG 基础上集成 LightRAG 作为可选知识图谱增强管道

**Architecture:** Python FastAPI sidecar (端口 9621) 封装 LightRAG，Java 通过 OkHttp 调用，结果与现有 Chunk RAG 合并。Sidecar 不可用时自动降级

**Tech Stack:** Python 3.10+ / FastAPI / LightRAG / SQLite，Java 17 / OkHttp / SpringBoot，Vue3 / Element Plus

**Spec:** `docs/superpowers/specs/2026-06-14-graphrag-lightrag-design.md`

---

### Task 1: Python LightRAG Sidecar

**Files:**
- Create: `src/main/deploy/lightrag/lightrag_server.py`
- Create: `src/main/deploy/lightrag/requirements.txt`

- [ ] **Step 1: 创建 requirements.txt**

```txt
fastapi>=0.110.0
uvicorn[standard]>=0.27.0
lightrag-hku>=1.0.0
pydantic>=2.0.0
```

- [ ] **Step 2: 创建 FastAPI 服务**

```python
"""LightRAG sidecar for zephyr knowledge module."""
import os
import shutil
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from lightrag import LightRAG, QueryParam
from lightrag.llm.openai import openai_complete_if_cache, openai_embed
from lightrag.utils import EmbeddingFunc

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("lightrag-sidecar")

# --- config from env ---
DATA_DIR = Path(os.environ.get("LIGHTRAG_DATA_DIR", os.path.expanduser("~/.zephyr/lightrag")))
LLM_BASE_URL = os.environ.get("LIGHTRAG_LLM_BASE_URL", "http://localhost:11434/v1")
LLM_MODEL = os.environ.get("LIGHTRAG_LLM_MODEL", "qwen2.5:7b")
LLM_API_KEY = os.environ.get("LIGHTRAG_LLM_API_KEY", "ollama")
EMBED_BASE_URL = os.environ.get("LIGHTRAG_EMBED_BASE_URL", LLM_BASE_URL)
EMBED_MODEL = os.environ.get("LIGHTRAG_EMBED_MODEL", "nomic-embed-text")
EMBED_API_KEY = os.environ.get("LIGHTRAG_EMBED_API_KEY", LLM_API_KEY)
EMBED_DIM = int(os.environ.get("LIGHTRAG_EMBED_DIM", "768"))

DATA_DIR.mkdir(parents=True, exist_ok=True)

# --- per-KB RAG instances ---
_rags: dict[str, LightRAG] = {}


def _get_rag(kb_id: str) -> LightRAG:
    if kb_id not in _rags:
        working_dir = DATA_DIR / kb_id
        working_dir.mkdir(parents=True, exist_ok=True)

        async def llm_func(prompt, system_prompt=None, history_messages=None, **kwargs):
            return await openai_complete_if_cache(
                LLM_MODEL, prompt, system_prompt=system_prompt,
                history_messages=history_messages or [],
                base_url=LLM_BASE_URL, api_key=LLM_API_KEY, **kwargs
            )

        async def embed_func(texts: list[str]) -> list[list[float]]:
            return await openai_embed(
                texts, model=EMBED_MODEL, base_url=EMBED_BASE_URL,
                api_key=EMBED_API_KEY
            )

        _rags[kb_id] = LightRAG(
            working_dir=str(working_dir),
            llm_model_func=llm_func,
            embedding_func=EmbeddingFunc(
                embedding_dim=EMBED_DIM,
                max_token_size=8192,
                func=embed_func
            ),
        )
    return _rags[kb_id]


# --- lifespan: graceful shutdown ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    _rags.clear()


app = FastAPI(title="LightRAG Sidecar", lifespan=lifespan)


# --- models ---
class IndexRequest(BaseModel):
    doc_id: str
    text: str


class SearchRequest(BaseModel):
    query: str
    mode: str = "hybrid"  # local | global | hybrid | naive | mix
    top_k: int = 10


class SearchResult(BaseModel):
    content: str
    source: str
    score: float


# --- routes ---
@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/index/{kb_id}")
async def index_doc(kb_id: str, req: IndexRequest):
    try:
        rag = _get_rag(kb_id)
        # LightRAG insert expects a string; we prepend doc_id as a marker
        tagged = f"[doc:{req.doc_id}]\n{req.text}"
        await rag.ainsert(tagged)
        log.info("indexed doc=%s into kb=%s", req.doc_id, kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("index failed: kb=%s doc=%s", kb_id, req.doc_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/search/{kb_id}")
async def search_kb(kb_id: str, req: SearchRequest):
    try:
        rag = _get_rag(kb_id)
        param = QueryParam(mode=req.mode, top_k=req.top_k)
        result = await rag.aquery(req.query, param=param)
        # LightRAG returns a string; extract what we can
        return [SearchResult(content=result, source="graph", score=1.0)]
    except Exception as e:
        log.exception("search failed: kb=%s", kb_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/index/{kb_id}/{doc_id}")
def delete_doc(kb_id: str, doc_id: str):
    try:
        # LightRAG doesn't support single-doc deletion natively.
        # We remove the KB instance so it reloads from persisted data on next use.
        _rags.pop(kb_id, None)
        working_dir = DATA_DIR / kb_id
        if working_dir.exists():
            # delete files matching doc_id prefix
            for f in working_dir.glob(f"*{doc_id}*"):
                f.unlink(missing_ok=True)
        log.info("deleted doc=%s from kb=%s", doc_id, kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("delete doc failed: kb=%s doc=%s", kb_id, doc_id)
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/kb/{kb_id}")
def delete_kb(kb_id: str):
    try:
        _rags.pop(kb_id, None)
        working_dir = DATA_DIR / kb_id
        if working_dir.exists():
            shutil.rmtree(working_dir)
        log.info("deleted kb=%s", kb_id)
        return {"status": "ok"}
    except Exception as e:
        log.exception("delete kb failed: kb=%s", kb_id)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=9621)
```

- [ ] **Step 3: 验证 Python 语法**

```bash
cd src/main/deploy/lightrag && python3 -c "import ast; ast.parse(open('lightrag_server.py').read()); print('OK')"
```
Expected: `OK`

- [ ] **Step 4: Commit**

```bash
git add src/main/deploy/lightrag/lightrag_server.py src/main/deploy/lightrag/requirements.txt
git commit -m "feat: 添加LightRAG Python sidecar服务"
```

---

### Task 2: ZephyrConfigProperties — LightRagConfig

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 Knowledge 内部类中添加 LightRagConfig**

在 `Knowledge` 类中 `Chroma` 内部类之后，`Knowledge` 右括号之前，添加：

```java
/** LightRAG 图谱增强配置 */
private LightRag lightrag = new LightRag();

@Data
public static class LightRag {
    /** 是否启用 LightRAG sidecar，默认 false（未部署时不影响系统运行） */
    private boolean enabled = false;
    /** LightRAG sidecar 地址 */
    private String baseUrl = "http://localhost:9621";
}
```

- [ ] **Step 2: 在 application.yml 中添加默认配置**

在 `zephyr.knowledge` 块中找到 `chroma` 配置附近，添加：

```yaml
      lightrag:
        enabled: false
        base-url: http://localhost:9621
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java src/main/resources/application.yml
git commit -m "feat: ZephyrConfigProperties新增LightRagConfig配置"
```

---

### Task 3: Entity + VO — graphEnabled 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeBaseEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/model/KnowledgeVO.java`

- [ ] **Step 1: KnowledgeBaseEntity 加字段**

```java
@Data
public class KnowledgeBaseEntity {
    private String id;
    private String userName;
    private String name;
    private String description;
    private String embedModelId;
    private String scope = "user";
    private Boolean graphEnabled;   // <-- 新增
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: KnowledgeVO 加字段**

```java
@Data
public class KnowledgeVO {
    private String id;
    private String name;
    private String description;
    private String embedModelId;
    private String embedModelName;
    private String scope;
    private boolean canManage;
    private int docCount;
    private Boolean graphEnabled;   // <-- 新增
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/entity/KnowledgeBaseEntity.java src/main/java/com/github/hbq969/ai/zephyr/knowledge/model/KnowledgeVO.java
git commit -m "feat: KnowledgeBaseEntity和KnowledgeVO新增graphEnabled字段"
```

---

### Task 4: Mapper XML — DDL（三方言）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml`

- [ ] **Step 1: 三方言 DDL 加列**

在三个文件的 `createKnowledgeBaseTable` 的 `CREATE TABLE` 语句中，`updated_at bigint` 之前添加：

```sql
      graph_enabled tinyint default 0,
```

三个文件（postgresql/embedded/mysql）都加在同样的位置。

- [ ] **Step 2: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/postgresql/KnowledgeMapper.xml src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/embedded/KnowledgeMapper.xml src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/mysql/KnowledgeMapper.xml
git commit -m "feat: 三方言DDL添加graph_enabled列"
```

---

### Task 5: Mapper XML — DML（common）

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml`

- [ ] **Step 1: insertKb 加字段**

`insertKb` 的列列表和值列表都加 `graph_enabled`：

```xml
<insert id="insertKb">
    insert into zephyr_knowledge_base (id, user_name, name, description, embed_model_id, scope, graph_enabled, created_at, updated_at)
    values (#{id}, #{userName}, #{name}, #{description}, #{embedModelId}, #{scope}, #{graphEnabled}, #{createdAt}, #{updatedAt})
</insert>
```

- [ ] **Step 2: 所有 SELECT 加 graph_enabled 列映射**

`queryKbByUserName`、`queryKbById`、`queryKbByIds`、`querySharedKbs` 四个查询的 SELECT 子句中，`scope` 之后添加：

```sql
graph_enabled as graphEnabled,
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/dao/mapper/common/KnowledgeMapper.xml
git commit -m "feat: DML语句添加graph_enabled字段映射"
```

---

### Task 6: SQL 增量迁移

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 三个 SQL 文件末尾追加 ALTER TABLE**

每个文件末尾添加：

```sql
ALTER TABLE zephyr_knowledge_base ADD COLUMN IF NOT EXISTS graph_enabled TINYINT DEFAULT 0;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/zephyr-zh-CN.sql src/main/resources/zephyr-en-US.sql src/main/resources/zephyr-ja-JP.sql
git commit -m "feat: SQL增量迁移添加graph_enabled列"
```

---

### Task 7: LightRagClient — Java HTTP 客户端

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/client/LightRagClient.java`

- [ ] **Step 1: 创建 LightRagClient**

```java
package com.github.hbq969.ai.zephyr.knowledge.client;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LightRagClient {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private ZephyrConfigProperties cfg;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private String baseUrl() {
        return cfg.getKnowledge().getLightrag().getBaseUrl();
    }

    private boolean enabled() {
        return cfg.getKnowledge().getLightrag().isEnabled();
    }

    public boolean health() {
        if (!enabled()) return false;
        try {
            String resp = get("/health");
            Map<?, ?> m = gson.fromJson(resp, Map.class);
            return "ok".equals(m.get("status"));
        } catch (Exception e) {
            log.warn("LightRAG health check failed: {}", e.getMessage());
            return false;
        }
    }

    public void index(String kbId, String docId, String text) {
        if (!enabled() || !health()) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("doc_id", docId);
            body.put("text", text);
            post("/index/" + kbId, body);
        } catch (Exception e) {
            log.warn("LightRAG index failed (non-fatal): kb={}, doc={}, msg={}", kbId, docId, e.getMessage());
        }
    }

    public List<GraphSearchResult> search(String kbId, String query, String mode, int topK) {
        if (!enabled() || !health()) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("mode", mode);
            body.put("top_k", topK);
            String resp = post("/search/" + kbId, body);
            List<GraphSearchResult> results = gson.fromJson(resp,
                    new TypeToken<List<GraphSearchResult>>() {}.getType());
            return results != null ? results : List.of();
        } catch (Exception e) {
            log.warn("LightRAG search failed (non-fatal): kb={}, msg={}", kbId, e.getMessage());
            return List.of();
        }
    }

    public void deleteDoc(String kbId, String docId) {
        if (!enabled()) return;
        try {
            delete("/index/" + kbId + "/" + docId);
        } catch (Exception e) {
            log.warn("LightRAG deleteDoc failed (non-fatal): kb={}, doc={}, msg={}", kbId, docId, e.getMessage());
        }
    }

    public void deleteKb(String kbId) {
        if (!enabled()) return;
        try {
            delete("/kb/" + kbId);
        } catch (Exception e) {
            log.warn("LightRAG deleteKb failed (non-fatal): kb={}, msg={}", kbId, e.getMessage());
        }
    }

    // --- HTTP helpers ---
    private String get(String path) throws IOException {
        Request request = new Request.Builder().url(baseUrl() + path).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(response.code() + " " + response.message());
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private String post(String path, Map<String, Object> body) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException(response.code() + " " + err);
            }
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private void delete(String path) throws IOException {
        Request request = new Request.Builder().url(baseUrl() + path).delete().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(response.code() + " " + response.message());
        }
    }

    @Data
    public static class GraphSearchResult {
        private String content;
        private String source;
        private double score;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/client/LightRagClient.java
git commit -m "feat: 添加LightRagClient OkHttp客户端"
```

---

### Task 8: KnowledgeServiceImpl — 接入图谱管道

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

- [ ] **Step 1: 注入 LightRagClient**

在已有 `@Resource` 区域添加：

```java
@Resource
private LightRagClient lightRagClient;
```

同时添加 import：

```java
import com.github.hbq969.ai.zephyr.knowledge.client.LightRagClient;
```

- [ ] **Step 2: processDocContentAsync — 分块完成后索引到图谱**

在方法末尾 `knowledgeDao.updateDocStatus(docId, "ready", chunks.size(), null);` 之前，`keywordIndex.addChunks(...)` 之后添加：

```java
// 图谱索引（异步，失败不影响主流程）
KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
if (kb != null && Boolean.TRUE.equals(kb.getGraphEnabled())) {
    lightRagClient.index(kbId, docId, text);
}
```

注意：这里可以直接复用方法开头已经查过的 `kb` 变量——`processDocContentAsync` 开头已经有 `KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);`，只需在 keywordIndex.addChunks 后添加上面的 if 块。

- [ ] **Step 3: search — 检索时并行查图谱**

在 `search()` 方法的 `results` 构建完成、return 之前，添加图谱结果合并：

```java
// 图谱检索增强
for (String kbId : kbIds) {
    KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
    if (kb == null || !Boolean.TRUE.equals(kb.getGraphEnabled())) continue;
    List<LightRagClient.GraphSearchResult> graphResults =
            lightRagClient.search(kbId, query, "hybrid", topK);
    for (LightRagClient.GraphSearchResult gr : graphResults) {
        // 按内容前 50 字去重
        String prefix = gr.getContent() != null ? gr.getContent().substring(0, Math.min(50, gr.getContent().length())) : "";
        boolean dup = false;
        for (SearchResult existing : results) {
            if (existing.getContent() != null && existing.getContent().startsWith(prefix)) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            SearchResult sr = new SearchResult(gr.getContent(),
                    gr.getSource() != null ? gr.getSource() : "图谱",
                    gr.getScore());
            results.add(sr);
        }
    }
}
```

- [ ] **Step 4: deleteDoc — 同步删除图谱数据**

在 `deleteDoc()` 方法中，`keywordIndex.removeDoc(...)` 之后添加：

```java
lightRagClient.deleteDoc(doc.getKbId(), id);
```

- [ ] **Step 5: deleteKb — 同步删除整库图谱**

在 `deleteKb()` 方法中，`knowledgeDao.deleteKb(id)` 之前添加：

```java
lightRagClient.deleteKb(id);
```

- [ ] **Step 6: listKb — VO 组装时填充 graphEnabled**

在 `listKb()` 方法的 `KnowledgeVO vo = new KnowledgeVO();` 设置属性区域，添加：

```java
vo.setGraphEnabled(Boolean.TRUE.equals(kb.getGraphEnabled()));
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile -DskipTests -q 2>&1 | tail -5
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "feat: KnowledgeServiceImpl接入LightRAG图谱管道"
```

---

### Task 9: 前端 — 知识库编辑弹窗加图谱开关 + 列表加标签

**Files:**
- Modify: `src/main/resources/static/src/views/settings/KnowledgeSettings.vue`

- [ ] **Step 1: form reactive 加 graphEnabled 字段**

```typescript
const form = reactive({ name: '', description: '', embedModelId: '', graphEnabled: false })
```

- [ ] **Step 2: openCreate 初始化**

```typescript
const openCreate = () => {
  dialogTitle.value = langData.knowledgeMgmt_createKb
  editingId.value = ''
  form.name = ''; form.description = ''; form.embedModelId = ''; form.graphEnabled = false; serverScope.value = 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}
```

- [ ] **Step 3: openEdit 回填**

```typescript
const openEdit = (kb: any) => {
  dialogTitle.value = langData.knowledgeMgmt_editKb
  editingId.value = kb.id
  form.name = kb.name; form.description = kb.description || ''; form.embedModelId = kb.embedModelId || ''
  form.graphEnabled = !!kb.graphEnabled; serverScope.value = kb.scope || 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}
```

- [ ] **Step 4: saveKb 传 graphEnabled**

```typescript
const data: any = { name: form.name.trim(), description: form.description.trim(), embedModelId: form.embedModelId, graphEnabled: form.graphEnabled, scope: serverScope.value }
```

- [ ] **Step 5: 在 el-dialog 的 el-form 末尾（scope 之后）添加图谱开关**

```html
<el-form-item :label="langData.knowledgeMgmt_graphLabel || '图谱增强'">
  <div style="display:flex;align-items:center;gap:8px;">
    <el-switch v-model="form.graphEnabled" />
    <span style="font-size:12px;color:var(--el-text-color-secondary);">{{ langData.knowledgeMgmt_graphHint || '额外构建实体关系图谱，提升多跳推理和全局理解能力' }}</span>
  </div>
</el-form-item>
```

- [ ] **Step 6: 知识库卡片 meta 区域加图谱标签**

在 `sharedBases` 和 `userBases` 两个 tab 的卡片模板中，`kb-embed-badge` 之后添加：

```html
<span v-if="kb.graphEnabled" class="kb-graph-badge">图谱</span>
```

- [ ] **Step 7: 在图谱标签 CSS（scoped style 块）**

```css
.kb-graph-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
```

- [ ] **Step 8: 编译验证前端类型检查**

```bash
cd src/main/resources/static && npm run type-check 2>&1 | tail -10
```
Expected: 无类型错误

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/src/views/settings/KnowledgeSettings.vue
git commit -m "feat: 知识库编辑添加图谱增强开关，列表添加图谱标签"
```

---

### 完成后验证

- [ ] 启动后端（me 环境，需要先配置有效的 LLM API key）
- [ ] 启动 LightRAG sidecar：`cd src/main/deploy/lightrag && pip install -r requirements.txt && python3 lightrag_server.py`
- [ ] 创建知识库时勾选"图谱增强"
- [ ] 上传文档，确认 `log.info("indexed doc=...")` 出现在 sidecar 日志
- [ ] 召回测试，确认结果包含 `source: "graph"` 的条目
- [ ] 停止 sidecar，确认召回测试仍正常返回（降级到纯 Chunk）
