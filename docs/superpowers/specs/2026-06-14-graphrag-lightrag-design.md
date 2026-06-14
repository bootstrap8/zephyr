# GraphRAG 增强 — 基于 LightRAG 的设计规格

## 概述

在现有 Chunk RAG 基础上，引入 LightRAG (MIT 开源) 作为可选的知识图谱增强管道。用户可按知识库开启，检索时 Chunk + 图谱两路并行，结果合并注入 LLM。

## 架构

```
文档上传
  ├─ Chunk 管道（不变）: TikaParser → TextSplitter → Embedding → ChromaDB + KeywordIndex
  └─ 图谱管道（可选）: 原始文本 → LightRagClient → LightRAG sidecar (Python, :9621)

检索时
  ├─ Chunk: RRF(向量 + 关键词) → N 条结果
  ├─ Graph: LightRAG hybrid search → M 条结果
  └─ 合并去重 → TopK → 注入 Chat Context
```

LightRAG sidecar 不可用时自动降级为纯 Chunk 检索，不影响主链路。

## LightRAG Sidecar API

FastAPI 服务，端口 9621，SQLite 存储。

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/index/{kb_id}` | 索引文本 `{doc_id, text}` |
| `POST` | `/search/{kb_id}` | 图谱检索 `{query, mode, top_k}` |
| `DELETE` | `/index/{kb_id}/{doc_id}` | 删除单文档图谱 |
| `DELETE` | `/kb/{kb_id}` | 删除整库图谱 |
| `GET` | `/health` | 健康检查 |

search mode: `hybrid`（默认）/ `local` / `global`。

## 文件变更

### 新增

- `src/main/deploy/lightrag/lightrag_server.py` — FastAPI 服务，封装 LightRAG
- `src/main/deploy/lightrag/requirements.txt` — Python 依赖
- `src/main/java/.../knowledge/client/LightRagClient.java` — OkHttp 客户端

### 修改

- `KnowledgeServiceImpl.java` — `processDocContentAsync`/`search`/`deleteDoc`/`deleteKb` 接入图谱管道
- `KnowledgeVO.java` — + `graphEnabled` 字段
- `KnowledgeBaseEntity.java` — + `graphEnabled` 字段
- `ZephyrConfigProperties.java` — + `LightRagConfig` 内部类
- Mapper XML — DDL（三方言）+ insert/select + SQL 增量迁移
- InitialServiceImpl.java — 空实现 no-op，字段由 ALTER TABLE 增量添加
- 前端知识库编辑弹窗 — + `el-switch` 开关
- 前端知识库列表 — 已开启的知识库显示"图谱"标签

### 数据库（已有表字段变更）

`zephyr_knowledge_base` 新增 `graph_enabled` 字段，按已有表加列 checklist：

- [ ] **Mapper XML DDL**：`postgresql`/`mysql`/`embedded` 三方言 `createKnowledgeBaseTable` 的 `CREATE TABLE` 语句加列
- [ ] **增量 DDL**：`knowledge-(zh-CN|en-US|ja-JP).sql` 中添加 `ALTER TABLE zephyr_knowledge_base ADD COLUMN graph_enabled TINYINT DEFAULT 0;`
- [ ] **DML 语句**：`common` 目录 Mapper XML 的 `insertKb`/`queryKb*` 语句加字段
- [ ] **实体类**：`KnowledgeBaseEntity` 加 `graphEnabled` 属性

### 配置

`application.yml` 新增：

```yaml
zephyr:
  knowledge:
    lightrag:
      enabled: false
      base-url: http://localhost:9621
```

## 关键设计决策

- **按需开启** — `graph_enabled` 默认 false，sidecar 未部署不影响系统运行
- **异步索引** — 复用现有 `@Async` 机制，不阻塞上传响应
- **异常降级** — LightRagClient 所有方法 catch 异常后 log.warn + 返回空/fallback
- **去重策略** — 图谱结果和 chunk 结果按 content 前 50 字符去重
- **存储** — LightRAG 用 SQLite，与项目 H2 风格一致，零运维
- **不做图可视化** — v1 只做检索增强，不在前端展示完整图
- **不做实体列表** — v1 不暴露图内部结构给用户

## 前端交互

- 知识库编辑弹窗：表单底部新增"启用图谱增强" el-switch，附带简短说明文字
- 知识库列表：graphEnabled=true 的知识库名称旁显示"图谱"标签
- 对话界面：无变化，图谱检索对用户透明
- 召回测试：搜索结果自动包含图谱来源结果（如有）
