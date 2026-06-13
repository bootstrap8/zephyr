# 知识库向量检索召回率优化

## 问题

现有知识库向量检索召回准确率低。典型场景：上传 20MB 接口文档（上百个接口），问"有哪些用户信息查询接口"，返回结果基本不相关。

## 原因分析

1. **分块粗放** — TextSplitter 分隔符 `\n\n → \n → 。 → 空格 → 硬切`，对中文 API 文档 `\n` 在 `。` 前面，接口描述被切成碎片
2. **语义漂移** — "用户信息查询"和"订单查询"在向量空间接近（同属"查询类接口"）
3. **缺少精确匹配** — 纯向量检索无法利用接口名、路径等关键词信号

## 方案：向量 + 关键词混合检索

### 架构

```
文件上传 → Tika 提取 → TextSplitter（改进）
                         ↓
              ┌──────────┴──────────┐
              ↓                     ↓
         向量化(Embedding)      FTS5 关键词索引
              ↓                     ↓
         Chroma 存储            SQLite FTS5
              └──────────┬──────────┘
                         ↓
                    用户查询
                    ↓
        ┌───────────┴───────────┐
        ↓                       ↓
   向量检索(topK×2)        关键词检索(topK×2)
        ↓                       ↓
        └───────────┬───────────┘
                    ↓
              RRF 融合排序
                    ↓
                  TopK
```

### 1. TextSplitter 改进

**分隔符优先级（新）：**

| 优先级 | 分隔符 | 原因 |
|--------|--------|------|
| 1 | Markdown 标题 (`##` `###` `####`) | 保持章节完整 |
| 2 | 代码块边界 (` ``` `) | 代码示例不切开 |
| 3 | 表格边界 | 参数表不切开 |
| 4 | 双换行 `\n\n` | 段落边界 |
| 5 | 中文标点（`。` `！` `？`） | 提到单换行前面 |
| 6 | 单换行 `\n` | |
| 7 | 硬切（达到 chunkSize 800） | |

**新增行为：**
- 每个 chunk 记录最近的章节标题作为**上下文前缀**，拼入 Chroma document 字段，标题词直接参与语义匹配
- `minChunkSize=200` 保护，避免连续标题产生小碎片

**尺寸：** 800 chars / overlap 150（不变）

### 2. FTS5 关键词索引

新增 `KeywordIndex` 组件，使用 SQLite FTS5 全文索引。

**建表：**
```sql
CREATE VIRTUAL TABLE knowledge_chunk_idx USING fts5(
    kb_id, doc_id, chunk_index, content
);
```

**索引时机：** `processDocAsync` 中向量化 + Chroma 存储后，逐 chunk 写入 FTS5

**删除时机：** 文档/知识库删除时级联清理索引

**查询流程：**
1. 原始 query 做轻量分词（中英文关键词提取）
2. `SELECT *, bm25(knowledge_chunk_idx) AS rank FROM knowledge_chunk_idx WHERE content MATCH ?`
3. 返回 BM25 分数

### 3. RRF 融合排序

使用 Reciprocal Rank Fusion 融合双路结果：

```
RRF_score(d) = Σ 1 / (k + rank_of_doc_in_path)    k=60
```

- 向量路取 topK × 2
- 关键词路取 topK × 2
- RRF 融合取 topK

### 4. 改动范围

| 文件 | 改动 |
|------|------|
| `TextSplitter.java` | 重写分隔符优先级 + 章节前缀 |
| `KeywordIndex.java` | **新增** — FTS5 索引管理 + 查询 |
| `RrfMerger.java` | **新增** — RRF 融合算法 |
| `KnowledgeServiceImpl.java` | 重构 `search()` 方法，双路检索 + 融合 |
| `KnowledgeDao.java` | 新增 FTS5 建表 DDL |
| `KnowledgeMapper.xml` | 三方言 DDL |
| `InitialServiceImpl.java` | 注册 FTS5 建表 |

### 5. 不改什么

- Embedding 模型不变（bge-m3）
- Chroma 存储不变
- Tika 解析不变
- 前端不变
- 现有 API 接口不变

## 预期效果

- 结构化文档（API 文档）召回率显著提升
- 关键词匹配覆盖语义漂移盲区
- 融合排序让双路匹配结果的排名更靠前
- 纯知识问答场景（无关键词）不影响，向量路仍然工作
