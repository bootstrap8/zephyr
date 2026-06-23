# 知识库召回准确率提升 — 第一期设计

## 目标

在不增加检索延迟、不依赖 LLM 在线调用的前提下，提升知识库检索召回率和准确率。

## 约束

- 索引阶段可以慢（加额外计算、调模型均可）
- 检索阶段必须快（路径上无 LLM 调用，延迟目标 < 500ms）
- LightRAG 图谱检索不参与在线检索（hybrid 模式调 LLM，延迟几分钟不可接受）

## 三项改动

### 1. BM25 关键词检索

**现状：** `KeywordIndex.search()` 使用纯词频打分 `termFreq / textLength`，无 IDF 权重，常见词和罕见词同等对待。

**目标：** 替换为标准 BM25 算法。

**BM25 公式：**

```
score(D, Q) = Σ IDF(qi) × (tf × (k1 + 1)) / (tf + k1 × (1 − b + b × docLen / avgdl))
```

其中：
- `IDF(qi) = log((N − df + 0.5) / (df + 0.5) + 1)`
- `N` = 知识库 chunk 总数
- `df` = 包含该词的 chunk 数量
- `k1 = 1.5`，`b = 0.75`
- `avgdl` = 全库 chunk 平均长度

**数据结构变更：**

```
term → Set<chunkId>           →  term → Map<chunkId, termFreq>
新增：int totalChunks          # 全库 chunk 总数
新增：float avgdl              # 全库平均 chunk 长度
```

**改动方法：**

| 方法 | 说明 |
|------|------|
| `addChunks()` | 记录词频，更新 df、totalChunks、avgdl |
| `removeDoc()` | 减词频/df，更新 totalChunks、avgdl |
| `search()` | TF 替换为 BM25 公式 |
| ~~`countTerm()`~~ | 删除，BM25 用词频表 |

**文件：** `KeywordIndex.java`

### 2. 上下文窗口扩展

**现状：** 检索返回单个 chunk，缺少前后文，LLM 回答时信息不足。

**做法：** RRF 融合得到 Top-K 后，对每个 chunk 查出同文档前后各 2 个相邻 chunk，合并连续区间去重后返回。

**窗口查询方法（KeywordIndex 新增）：**

```java
// chunkId = "abc_5" → 返回 ["abc_3", "abc_4", "abc_5", "abc_6", "abc_7"]
public List<String> expandWindow(String kbId, String chunkId, int window)
```

利用 `chunkId` 格式 `{docId}_{chunkIndex}` 和已有 `chunkTexts` 映射实现。

**合并去重逻辑（KnowledgeServiceImpl.search() 新增）：**

```
Top-1 chunk "abc_5" → 窗口 [abc_3..abc_7]
Top-3 chunk "abc_6" → 窗口 [abc_4..abc_8]
合并为 [abc_3..abc_8]，避免重复返回重叠区间
```

**文件：** `KeywordIndex.java`（加方法）、`KnowledgeServiceImpl.java`（调 expandWindow + 合并）

### 3. 查询增强

**问题：** 用户查询通常 5-15 字，直接 embedding 后和 800 字 chunk 做相似度，语义空间不对齐。

**做法：** embedding 之前从查询中提取关键词拼接到原查询后面，用更丰富的文本做向量化。

**示例：**

```
原查询："如何配置MCP服务器超时时间"
增强后："如何配置MCP服务器超时时间 MCP服务器 配置 超时时间 服务器"
```

**提取规则（复用已有令牌化逻辑）：**
- 中文：2-4 字连续片段，去重
- 英文：长度 ≥ 3 的 token，去重
- 增强后长度控制在原查询的 1-2 倍

**改动点：** `KnowledgeServiceImpl.search()` 中 embedding 前加一行 `augmentQuery()`

**文件：** `KnowledgeServiceImpl.java`

## 改造后检索流程

```
查询 → 查询增强 → Embedding → ChromaDB 向量 Top-50 ──┐
                                                      ├→ RRF(Top-10) → 窗口扩展 → 返回
查询 → 分词 → BM25 关键词 Top-50 ────────────────────┘
```

无 LLM 调用，无 LightRAG 在线检索。全路径延迟预期 < 500ms。

## 工作量

| 改动 | 文件 | 量级 |
|------|------|------|
| BM25 | `KeywordIndex.java` | 重构内部实现 |
| 上下文窗口 | `KeywordIndex.java` + `KnowledgeServiceImpl.java` | +1 方法 + 调 1 处 |
| 查询增强 | `KnowledgeServiceImpl.java` | +1 方法 + 改 1 行 |

三项改动互不依赖，可独立开发、独立验证。

## 验证方式

每项改动完成后，用 curl 调 `/zephyr-ui/knowledge/search` 接口：
1. 对比改动前后相同查询的 Top-5 结果
2. 人工判断 Top-K 结果与查询的相关性
3. 确认检索耗时在 500ms 以内
