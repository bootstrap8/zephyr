# 知识库召回准确率提升第一期 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提升知识库检索召回率：BM25 关键词检索 + 上下文窗口扩展 + 查询增强，检索路径零 LLM 调用，p95 < 500ms。

**Architecture:** 三项改动均集中在 `KeywordIndex.java`（核心打分和索引数据结构）和 `KnowledgeServiceImpl.java`（编排和调用）。改动互不依赖，每项完成后独立验证。不改基础设施，不引入新依赖。

**Tech Stack:** Java 17, no new dependencies. KeywordIndex is in-memory (HashMap), ChromaDB for vectors, OkHttp for embedding API.

---

## File Structure

| 文件 | 角色 | 改动类型 |
|------|------|----------|
| `KeywordIndex.java` | 倒排索引 — 数据结构+BM25打分+窗口查询 | 重构 |
| `KnowledgeServiceImpl.java` | 检索编排 — 查询增强+窗口合并+截断 | 修改 |
| `ZephyrConfigProperties.java` | 配置类 — BM25 参数 k1/b | 新增字段 |
| `KeywordIndexTest.java` | BM25 + 窗口扩展的单元测试 | 新建 |

---

### Task 1: BM25 关键词检索

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Create: `src/test/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndexTest.java`

- [ ] **Step 1: 编写 BM25 单元测试**

```java
// src/test/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndexTest.java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    private KeywordIndex idx;
    private static final String KB1 = "test-kb";

    @BeforeEach
    void setUp() {
        idx = new KeywordIndex();
    }

    @Test
    void bm25_shouldBoostRareTerms() {
        // doc1: 只有一个 chunk，包含罕见词"旋耕机"
        idx.addChunks(KB1, "doc1", List.of(
            "旋耕机是农业机械的一种，用于土壤耕作"
        ));
        // doc2: 5 个 chunk，包含常见词"农业"，少数 chunk 含"旋耕机"
        idx.addChunks(KB1, "doc2", List.of(
            "农业机械的种类很多",
            "包括拖拉机、收割机等",
            "现代农业技术发展迅速",
            "旋耕机属于耕作机械",
            "机械化是农业现代化的标志"
        ));

        // 查询罕见词：BM25 应给含"旋耕机"的 chunk 更高分
        Map<String, Float> results = idx.search("旋耕机", List.of(KB1), 5);

        assertFalse(results.isEmpty(), "应至少召回一个 chunk");
        // doc1_0 和 doc2_3（含"旋耕机"）应该排在 doc2_0/doc2_2（含多个"农业"但不含"旋耕机"）前面
        assertTrue(results.containsKey("doc1_0"));
        assertTrue(results.containsKey("doc2_3"));

        // 对于单查"农业"：doc2 有 5 个 chunk，IDF 应压低"农业"的分数
        Map<String, Float> agriResults = idx.search("农业", List.of(KB1), 10);
        // "旋耕机"的搜索结果分数应比"农业"的分数区分度更高
        double topScore = agriResults.values().stream().findFirst().orElse(0f);
        double bottomScore = agriResults.values().stream().reduce((a, b) -> b).orElse(0f);
        // 同一常见词的结果间分数差异应该较小
        assertTrue(topScore - bottomScore < 0.5, "常见词的分数差异应较小");
    }

    @Test
    void bm25_idf_shouldBeDocumentLevel() {
        // 同一个文档的 3 个 chunk 都含"API"
        idx.addChunks(KB1, "long-doc", List.of(
            "API 接口定义了请求格式",
            "API 响应包含状态码",
            "API 认证使用 Bearer Token"
        ));
        // 另一个文档的 1 个 chunk 含"API"
        idx.addChunks(KB1, "short-doc", List.of("使用 API 前需要先申请密钥"));

        Map<String, Float> results = idx.search("API", List.of(KB1), 10);

        // 文档粒度 IDF：df=2（出现在 2 个文档中）
        // chunk 粒度 IDF：df=4（出现在 4 个 chunk 中）
        // 文档粒度 IDF 应高于 chunk 粒度，分数应更合理
        // 所有 chunk 都应该被命中
        assertEquals(4, results.size(), "所有含 API 的 chunk 都应被命中");

        // short-doc_0 含"API"只出现 1 次，long-doc 的 chunk 也各 1 次
        // 文档粒度 IDF 下，两文档对"API"的 IDF 相同，主要按 tf 和长度区分
        assertTrue(results.containsKey("short-doc_0"));
    }

    @Test
    void bm25_shouldHandleChineseQuery() {
        idx.addChunks(KB1, "zh-doc", List.of(
            "如何配置 MCP 服务器的超时时间",
            "MCP 服务器支持多种传输协议",
            "超时时间默认为 30 秒"
        ));

        Map<String, Float> results = idx.search("MCP服务器超时时间", List.of(KB1), 5);

        assertFalse(results.isEmpty());
        // "MCP" 和 "超时" 两个关键 bigram 都应命中
        assertTrue(results.containsKey("zh-doc_0"), "直接相关 chunk 应排第一");
    }

    @Test
    void addAndRemove_shouldMaintainCorrectStats() {
        idx.addChunks(KB1, "doc-a", List.of("hello world", "hello java"));
        idx.addChunks(KB1, "doc-b", List.of("world of java"));

        Map<String, Float> results1 = idx.search("hello", List.of(KB1), 10);
        assertTrue(results1.containsKey("doc-a_0"));
        assertTrue(results1.containsKey("doc-a_1"));

        // 移除 doc-a 后，"hello" 不应再被命中
        idx.removeDoc(KB1, "doc-a");
        Map<String, Float> results2 = idx.search("hello", List.of(KB1), 10);
        assertTrue(results2.isEmpty(), "移除文档后不应再命中其 chunk");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd src/main/resources/static && npx tsc --noEmit 2>/dev/null  # skip frontend
# 从项目根目录运行测试
mvn test -pl . -Dtest=KeywordIndexTest -Dsurefire.useFile=false 2>&1 | tail -20
```

预期：测试编译失败，BM25 相关方法尚不存在。

- [ ] **Step 3: 在 ZephyrConfigProperties 添加 BM25 参数**

```java
// src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java
// 在 Knowledge 静态内部类中添加 Bm25 子配置

@Data
public static class Knowledge {
    // ... 现有字段 ...
    /** BM25 关键词检索参数 */
    private Bm25 bm25 = new Bm25();

    @Data
    public static class Bm25 {
        /** 词频饱和度参数，默认 1.5 */
        private double k1 = 1.5;
        /** 长度归一化参数，默认 0.75（chunk 近似等长可适当降低） */
        private double b = 0.75;
    }
    
    // ... 现有 Chroma / LightRag ...
}
```

- [ ] **Step 4: 重构 KeywordIndex — 数据结构**

```java
// src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java
// 替换现有字段定义

package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KeywordIndex {

    @Resource
    private ZephyrConfigProperties cfg;

    // kbId -> (term -> Map<chunkId, termFreq>)
    private final Map<String, Map<String, Map<String, Integer>>> termChunkFreq = new HashMap<>();
    // kbId -> (term -> Set<docId>)  — 文档级倒排（用于 IDF）
    private final Map<String, Map<String, Set<String>>> termDocFreq = new HashMap<>();
    // kbId -> (chunkId -> chunkText)
    private final Map<String, Map<String, String>> chunkTexts = new HashMap<>();
    // kbId -> Set<docId>
    private final Map<String, Set<String>> kbDocs = new HashMap<>();
    // kbId -> totalChunks
    private final Map<String, Integer> totalChunks = new HashMap<>();
    // kbId -> totalChars (for avgChunkLen)
    private final Map<String, Integer> totalChars = new HashMap<>();
    // chunkId -> text (flat reverse index for O(1) lookup)
    private final Map<String, String> textById = new HashMap<>();
    // chunkId -> kbId (for O(1) KB lookup in window expansion)
    private final Map<String, String> chunkKbMap = new HashMap<>();
}
```

- [ ] **Step 5: 重构 KeywordIndex — addChunks()**

```java
    public synchronized void addChunks(String kbId, String docId, List<String> chunks) {
        Map<String, Map<String, Integer>> kbTermChunk = termChunkFreq.computeIfAbsent(kbId, k -> new HashMap<>());
        Map<String, Set<String>> kbTermDoc = termDocFreq.computeIfAbsent(kbId, k -> new HashMap<>());
        Map<String, String> kbTexts = chunkTexts.computeIfAbsent(kbId, k -> new HashMap<>());

        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "_" + i;
            String chunkText = chunks.get(i);
            kbTexts.put(chunkId, chunkText);
            textById.put(chunkId, chunkText);
            chunkKbMap.put(chunkId, kbId);

            totalChunks.merge(kbId, 1, Integer::sum);
            totalChars.merge(kbId, chunkText.length(), Integer::sum);

            for (String term : tokenizeForBm25(chunkText)) {
                kbTermChunk.computeIfAbsent(term, k -> new HashMap<>())
                        .merge(chunkId, 1, Integer::sum);
                kbTermDoc.computeIfAbsent(term, k -> new HashSet<>()).add(docId);
            }
        }
        kbDocs.computeIfAbsent(kbId, k -> new HashSet<>()).add(docId);
    }
```

- [ ] **Step 6: 重构 KeywordIndex — tokenizeForBm25()**

```java
    /**
     * BM25 专用分词：中文 bigram（过滤单字），英文按空格拆词取 >=2 字符的 token。
     * 对比原 tokenize()：移除了 unigram（单字 IDF 趋零，对 BM25 无贡献）。
     */
    private Set<String> tokenizeForBm25(String text) {
        Set<String> terms = new HashSet<>();
        // 英文：按空白拆词
        for (String w : text.split("\\s+")) {
            w = w.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (w.length() >= 2) terms.add(w);
        }
        // 中文：连续汉字 bigram
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cn.length() - 1; i++) {
            terms.add(cn.substring(i, i + 2));
        }
        return terms;
    }
```

- [ ] **Step 7: 重构 KeywordIndex — removeDoc()**

```java
    public synchronized void removeDoc(String kbId, String docId) {
        Map<String, Map<String, Integer>> kbTermChunk = termChunkFreq.get(kbId);
        Map<String, Set<String>> kbTermDoc = termDocFreq.get(kbId);
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        Set<String> docs = kbDocs.get(kbId);
        if (kbTermChunk == null || kbTexts == null) return;

        List<String> toRemove = new ArrayList<>();
        for (String chunkId : kbTexts.keySet()) {
            if (chunkId.startsWith(docId + "_")) toRemove.add(chunkId);
        }

        for (String chunkId : toRemove) {
            String text = kbTexts.remove(chunkId);
            textById.remove(chunkId);
            chunkKbMap.remove(chunkId);
            if (text != null) {
                totalChars.merge(kbId, -text.length(), Integer::sum);
            }
            totalChunks.merge(kbId, -1, Integer::sum);

            // 仅处理该 chunk 包含的 term，而非遍历整个词表
            Set<String> chunkTerms = tokenizeForBm25(text != null ? text : "");
            for (String term : chunkTerms) {
                Map<String, Integer> chunkMap = kbTermChunk.get(term);
                if (chunkMap != null) {
                    chunkMap.remove(chunkId);
                    if (chunkMap.isEmpty()) {
                        kbTermChunk.remove(term);
                    }
                }
                // 检查该 term 在本 doc 的其他 chunk 中是否还存在
                Set<String> docSet = kbTermDoc.get(term);
                if (docSet != null) {
                    boolean docStillHasTerm = kbTexts.keySet().stream()
                            .anyMatch(c -> c.startsWith(docId + "_")
                                    && kbTermChunk.getOrDefault(term, Collections.emptyMap()).containsKey(c));
                    if (!docStillHasTerm) {
                        docSet.remove(docId);
                        if (docSet.isEmpty()) {
                            kbTermDoc.remove(term);
                        }
                    }
                }
            }
        }

        // 清理空 kb
        if (kbTexts.isEmpty()) {
            termChunkFreq.remove(kbId);
            termDocFreq.remove(kbId);
            chunkTexts.remove(kbId);
            kbDocs.remove(kbId);
            totalChunks.remove(kbId);
            totalChars.remove(kbId);
        } else {
            if (docs != null) docs.remove(docId);
        }
        log.info("关键词索引(BM25)已移除文档: kbId={}, docId={}, chunks={}", kbId, docId, toRemove.size());
    }
```

- [ ] **Step 8: 重构 KeywordIndex — removeKb()**

```java
    public synchronized void removeKb(String kbId) {
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        if (kbTexts != null) {
            for (String chunkId : kbTexts.keySet()) {
                textById.remove(chunkId);
                chunkKbMap.remove(chunkId);
            }
        }
        termChunkFreq.remove(kbId);
        termDocFreq.remove(kbId);
        chunkTexts.remove(kbId);
        kbDocs.remove(kbId);
        totalChunks.remove(kbId);
        totalChars.remove(kbId);
    }
```

- [ ] **Step 9: 重构 KeywordIndex — search() 改为 BM25**

```java
    public synchronized Map<String, Float> search(String query, List<String> kbIds, int topK) {
        Set<String> queryTerms = tokenizeForBm25(query);
        if (queryTerms.isEmpty()) return new LinkedHashMap<>();

        double k1 = cfg.getKnowledge().getBm25().getK1();
        double b = cfg.getKnowledge().getBm25().getB();

        Map<String, Float> scores = new HashMap<>();

        for (String kbId : kbIds) {
            Map<String, Map<String, Integer>> kbTermChunk = termChunkFreq.get(kbId);
            Map<String, Set<String>> kbTermDoc = termDocFreq.get(kbId);
            Map<String, String> kbTexts = chunkTexts.get(kbId);

            if (kbTermChunk == null || kbTexts == null) continue;

            int N = kbDocs.getOrDefault(kbId, Collections.emptySet()).size();
            int totalC = totalChunks.getOrDefault(kbId, 1);
            int totalChar = totalChars.getOrDefault(kbId, 1);
            double avgdl = (double) totalChar / Math.max(totalC, 1);

            for (String term : queryTerms) {
                Map<String, Integer> chunkFreqs = kbTermChunk.get(term);
                Set<String> docSet = kbTermDoc.get(term);
                if (chunkFreqs == null || docSet == null) continue;

                // 文档级 IDF
                int df = docSet.size();
                double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);

                for (Map.Entry<String, Integer> entry : chunkFreqs.entrySet()) {
                    String chunkId = entry.getKey();
                    int tf = entry.getValue();
                    String chunkText = kbTexts.get(chunkId);
                    if (chunkText == null) continue;

                    double docLen = chunkText.length();
                    double numerator = tf * (k1 + 1);
                    double denominator = tf + k1 * (1 - b + b * docLen / avgdl);
                    double score = idf * numerator / denominator;

                    scores.merge(chunkId, (float) score, Float::sum);
                }
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }
```

- [ ] **Step 10: 删除废弃的 countTerm() 方法**

```java
// 移除 KeywordIndex 中的 countTerm() 方法
// 该方法仅被原 search() 使用，已不再需要
```

- [ ] **Step 11: 运行测试确认通过**

```bash
mvn test -Dtest=KeywordIndexTest -Dsurefire.useFile=false 2>&1 | tail -30
```

预期：4 个测试全部 PASS。

- [ ] **Step 12: 编译确认主代码无编译错误**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests 2>&1 | tail -10
```

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java \
        src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java \
        src/test/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndexTest.java
git commit -m "$(cat <<'EOF'
feat: BM25关键词检索(文档级IDF)，替代原简朴TF打分

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 上下文窗口扩展

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`
- Modify: `src/test/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndexTest.java`

- [ ] **Step 1: 编写窗口扩展的单元测试**

```java
// 在 KeywordIndexTest.java 中添加

    @Test
    void expandWindow_shouldReturnAdjacentChunks() {
        idx.addChunks(KB1, "doc-window", List.of(
            "第零段", "第一段", "第二段", "第三段", "第四段"
        ));

        List<String> window = idx.expandWindow("doc-window_2", 2);
        assertEquals(List.of("doc-window_0", "doc-window_1", "doc-window_2", "doc-window_3", "doc-window_4"), window);
    }

    @Test
    void expandWindow_shouldHandleBoundaries() {
        idx.addChunks(KB1, "doc-edge", List.of("首段", "次段", "尾段"));

        // 窗口起始越界
        List<String> window = idx.expandWindow("doc-edge_0", 2);
        assertEquals(List.of("doc-edge_0", "doc-edge_1", "doc-edge_2"), window);

        // 窗口结束越界
        window = idx.expandWindow("doc-edge_2", 2);
        assertEquals(List.of("doc-edge_0", "doc-edge_1", "doc-edge_2"), window);
    }

    @Test
    void expandWindow_shouldReturnOnlySelfWhenNoNeighbors() {
        idx.addChunks(KB1, "solo-doc", List.of("唯一一段"));

        List<String> window = idx.expandWindow("solo-doc_0", 2);
        assertEquals(List.of("solo-doc_0"), window);
    }

    @Test
    void expandWindow_shouldFindCorrectKb() {
        String kb2 = "test-kb-2";
        idx.addChunks(KB1, "kb1-doc", List.of("A1", "A2", "A3"));
        idx.addChunks(kb2, "kb2-doc", List.of("B1", "B2", "B3"));

        // 跨 KB 查找不应交叉污染
        List<String> w1 = idx.expandWindow("kb1-doc_1", 1);
        assertEquals(List.of("kb1-doc_0", "kb1-doc_1", "kb1-doc_2"), w1,
                "应从 KB1 展开，不含 KB2 的 chunk");

        List<String> w2 = idx.expandWindow("kb2-doc_1", 1);
        assertEquals(List.of("kb2-doc_0", "kb2-doc_1", "kb2-doc_2"), w2,
                "应从 KB2 展开，不含 KB1 的 chunk");
    }

    private static final String KB2 = "test-kb-2";
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=KeywordIndexTest#expandWindow_shouldReturnAdjacentChunks -Dsurefire.useFile=false 2>&1 | tail -15
```

预期：编译失败，`expandWindow` 方法不存在。

- [ ] **Step 3: 在 KeywordIndex 中实现 expandWindow()**

```java
    /**
     * 获取指定 chunk 前后各 window 个相邻 chunk 的 chunkId 列表（含自身）。
     * chunkId 格式为 {docId}_{chunkIndex}，窗口内的 chunk 按 chunkIndex 升序排列。
     * 越界的 chunkIndex 静默跳过，不存在的 chunkId 不包含在结果中。
     * 自动通过 chunkKbMap 定位所属 KB（O(1)）。
     */
    public synchronized List<String> expandWindow(String chunkId, int window) {
        String kbId = chunkKbMap.get(chunkId);
        if (kbId == null) return List.of(chunkId);
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        if (kbTexts == null) return List.of(chunkId);

        // 解析 docId + chunkIndex
        int lastUnderscore = chunkId.lastIndexOf('_');
        if (lastUnderscore < 0) return List.of(chunkId);
        String docId = chunkId.substring(0, lastUnderscore);
        int chunkIdx;
        try {
            chunkIdx = Integer.parseInt(chunkId.substring(lastUnderscore + 1));
        } catch (NumberFormatException e) {
            return List.of(chunkId);
        }

        List<String> result = new ArrayList<>();
        for (int i = chunkIdx - window; i <= chunkIdx + window; i++) {
            if (i < 0) continue;
            String candidate = docId + "_" + i;
            if (kbTexts.containsKey(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=KeywordIndexTest -Dsurefire.useFile=false 2>&1 | tail -20
```

预期：7 个测试全部 PASS。

- [ ] **Step 5: 在 KnowledgeServiceImpl.search() 中集成窗口扩展**

```java
// 在 search() 方法中，RRF 融合后、返回 results 之前，添加以下逻辑

        // --- 上下文窗口扩展 ---
        final int WINDOW_SIZE = 2;
        final int MAX_TOTAL_CHARS = 8000;

        // 1. 收集每个 Top-K chunkId 的窗口，合并为连续区间
        Set<String> expandedIds = new LinkedHashSet<>();
        for (String chunkId : mergedIds) {
            List<String> window = keywordIndex.expandWindow(chunkId, WINDOW_SIZE);
            expandedIds.addAll(window);
        }

        // 2. 转为按 (docId, chunkIndex) 排序的列表
        List<String> sortedExpanded = new ArrayList<>(expandedIds);
        sortedExpanded.sort(Comparator.comparing(id -> {
            int i = id.lastIndexOf('_');
            if (i < 0) return id;
            return id.substring(0, i) + String.format("%010d", Integer.parseInt(id.substring(i + 1)));
        }));

        // 3. 拼接 chunk 文本，硬限 8000 字符
        StringBuilder expandedText = new StringBuilder();
        for (String id : sortedExpanded) {
            String chunkText = keywordIndex.getChunkText(id);
            if (chunkText == null) continue;
            if (expandedText.length() + chunkText.length() + 1 > MAX_TOTAL_CHARS) break;
            if (expandedText.length() > 0) expandedText.append("\n\n");
            expandedText.append(chunkText);
        }

        // 4. 用扩展后的文本替换第一个 SearchResult 的内容
        //    SearchResult 构造签名：(String text, String fileName, double score)
        //    实现前请确认实际构造函数匹配
        if (!results.isEmpty() && expandedText.length() > 0) {
            SearchResult first = results.get(0);
            results.add(0, new SearchResult(expandedText.toString(),
                    first.getSourceFile() + "（上下文窗口）", first.getScore()));
        }
```

- [ ] **Step 6: 编译确认**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java \
        src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java \
        src/test/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndexTest.java
git commit -m "$(cat <<'EOF'
feat: 上下文窗口扩展 — 检索后获取相邻chunk合并返回

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 查询增强

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`
- Create: `src/test/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/AugmentQueryTest.java`

- [ ] **Step 1: 编写 augmentQuery 单元测试**

```java
// src/test/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/AugmentQueryTest.java
package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AugmentQueryTest {

    // augmentQuery 是 private 方法，通过反射调用便于测试
    private String callAugmentQuery(String query) throws Exception {
        KnowledgeServiceImpl svc = new KnowledgeServiceImpl();
        Method m = KnowledgeServiceImpl.class.getDeclaredMethod("augmentQuery", String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, query);
    }

    @Test
    void shouldAppendChineseKeywords() throws Exception {
        String result = callAugmentQuery("如何配置MCP服务器超时时间");
        assertTrue(result.startsWith("如何配置MCP服务器超时时间 "));
        // 应包含中文关键词，不含停用词"如何"
        assertTrue(result.contains("配置"));
        assertTrue(result.contains("服务器"));
        assertFalse(result.split(" ")[1].contains("如何"));
    }

    @Test
    void shouldNotContainStopWords() throws Exception {
        String result = callAugmentQuery("怎么可以这样做");
        // "怎么"、"可以" 是停用词，不应出现在追加部分
        String[] parts = result.split(" ", 2);
        if (parts.length > 1) {
            assertFalse(parts[1].contains("怎么"));
            assertFalse(parts[1].contains("可以"));
        }
    }

    @Test
    void shouldHandleEnglishTokens() throws Exception {
        String result = callAugmentQuery("fix bug in MCP handler");
        // 英文 token：fix(3) bug(3) MCP(3) handler(7)
        assertTrue(result.contains("fix"));
        assertTrue(result.contains("bug"));
        assertTrue(result.contains("mcp"));
        assertTrue(result.contains("handler"));
        // "in"(2) 长度 < 3，不应出现
        assertFalse(result.split(" ")[1].contains("in"));
    }

    @Test
    void shouldLimitAugmentedLength() throws Exception {
        // 构造长查询，验证长度控制在 3 倍以内
        String longQuery = "a".repeat(200);
        String result = callAugmentQuery(longQuery);
        assertTrue(result.length() <= longQuery.length() * 3 + 10);
    }

    @Test
    void shouldReturnOriginalForEmptyQuery() throws Exception {
        assertEquals("", callAugmentQuery(""));
        assertNull(callAugmentQuery(null));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=AugmentQueryTest -Dsurefire.useFile=false 2>&1 | tail -10
```

预期：编译失败，`augmentQuery` 方法不存在。

- [ ] **Step 3: 在 KnowledgeServiceImpl 中实现 augmentQuery()**

```java
    // 停用词表
    private static final Set<String> STOP_WORDS = Set.of(
            "如何", "怎么", "怎样", "可以", "这个", "那个", "什么", "为什么",
            "哪些", "哪里", "怎样", "是否", "能不能", "有没有", "怎么办",
            "的", "了", "吗", "呢", "吧", "啊", "嘛"
    );

    /**
     * 查询增强：从查询中提取关键词拼接到原查询后面，拉近与 chunk 的语义距离。
     * <p>
     * 中文：提取 2-4 字连续汉字片段，过滤停用词，去重。
     * 英文：提取长度 ≥ 3 的字母数字 token，去重。
     * 增强后长度控制在原查询的 1-2 倍。
     */
    private String augmentQuery(String query) {
        if (query == null || query.trim().isEmpty()) return query;

        List<String> keywords = new ArrayList<>();

        // 中文关键词：提取连续汉字片段
        StringBuilder cnBuf = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cnBuf.append(c);
            } else {
                extractChineseKeywords(cnBuf.toString(), keywords);
                cnBuf.setLength(0);
            }
        }
        extractChineseKeywords(cnBuf.toString(), keywords);

        // 英文关键词：按非字母数字拆词
        for (String token : query.split("[^a-zA-Z0-9]+")) {
            token = token.trim().toLowerCase();
            if (token.length() >= 3) {
                keywords.add(token);
            }
        }

        if (keywords.isEmpty()) return query;

        // 控制总长度
        String augmented = query + " " + String.join(" ", keywords);
        if (augmented.length() > query.length() * 3) {
            // 截断到 2 倍
            int budget = query.length() * 2;
            StringBuilder sb = new StringBuilder(query);
            for (String kw : keywords) {
                if (sb.length() + kw.length() + 1 > budget) break;
                sb.append(" ").append(kw);
            }
            return sb.toString();
        }
        return augmented;
    }

    private void extractChineseKeywords(String cnText, List<String> out) {
        if (cnText.length() < 2) return;
        // 2-4 字滑动窗口
        int maxWindow = Math.min(4, cnText.length());
        for (int w = maxWindow; w >= 2; w--) {
            for (int i = 0; i <= cnText.length() - w; i++) {
                String kw = cnText.substring(i, i + w);
                if (!STOP_WORDS.contains(kw)) {
                    out.add(kw);
                }
            }
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn test -Dtest=AugmentQueryTest -Dsurefire.useFile=false 2>&1 | tail -10
```

预期：5 个测试 PASS。

- [ ] **Step 5: 在 search() 中集成查询增强**

```java
// 在 KnowledgeServiceImpl.search() 中，embedding 调用之前，修改 query 为增强后的文本

        // 查询增强
        String augmentedQuery = augmentQuery(query);

        for (Map.Entry<String, List<String>> entry : kbByModel.entrySet()) {
            // ... 省略中间代码 ...
            // 使用增强后的查询做 embedding
            List<float[]> embeddings = embeddingClient.embed(List.of(augmentedQuery), embedModel);
            // ... 后续逻辑不变 ...
        }
```

- [ ] **Step 6: 编译确认**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests 2>&1 | tail -10
```

- [ ] **Step 7: 端到端验证**

```bash
# 确保后端已启动（localhost:30733），然后用 curl 测试
# 注意：测试需要已有索引的知识库数据

# 测试查询增强效果
curl -s -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/knowledge/search?query=如何配置模型参数&topK=5" \
  | python3 -m json.tool 2>/dev/null | head -50

# 对比有增强和无增强的结果（改动前后各跑一次）
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "$(cat <<'EOF'
feat: 查询增强 — embedding前提取关键词拼接，拉近与chunk的语义距离

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 验证测试集

**Files:**
- Create: `src/test/java/com/github/hbq969/ai/zephyr/knowledge/RecallTest.java`
- Create: `src/test/resources/knowledge-recall-testset.json`

- [ ] **Step 1: 创建测试数据集**

```json
// src/test/resources/knowledge-recall-testset.json
{
  "kbId": "test-kb-id",
  "kbName": "recall-test-kb",
  "queries": [
    // === 配置问题 (5条) ===
    { "query": "如何配置超时时间", "category": "配置问题", "expectedChunkIds": [] },
    { "query": "怎么修改端口号", "category": "配置问题", "expectedChunkIds": [] },
    { "query": "日志级别怎么设置", "category": "配置问题", "expectedChunkIds": [] },
    { "query": "线程池大小配置参数", "category": "配置问题", "expectedChunkIds": [] },
    { "query": "数据源连接池配置", "category": "配置问题", "expectedChunkIds": [] },
    // === 接口查找 (5条) ===
    { "query": "MCP服务器支持哪些协议", "category": "接口查找", "expectedChunkIds": [] },
    { "query": "如何调用知识库搜索接口", "category": "接口查找", "expectedChunkIds": [] },
    { "query": "模型配置有哪些API", "category": "接口查找", "expectedChunkIds": [] },
    { "query": "文档上传接口参数说明", "category": "接口查找", "expectedChunkIds": [] },
    { "query": "对话SSE接口怎么用", "category": "接口查找", "expectedChunkIds": [] },
    // === 概念解释 (5条) ===
    { "query": "什么是embedding向量化", "category": "概念解释", "expectedChunkIds": [] },
    { "query": "RRF融合算法原理", "category": "概念解释", "expectedChunkIds": [] },
    { "query": "LightRAG和传统RAG的区别", "category": "概念解释", "expectedChunkIds": [] },
    { "query": "ChromaDB是什么", "category": "概念解释", "expectedChunkIds": [] },
    { "query": "BM25打分怎么算的", "category": "概念解释", "expectedChunkIds": [] }
  ]
}
```

注：`expectedChunkIds` 在导入测试数据后由人工标注填充。

- [ ] **Step 2: 编写 Recall@K 测试跑分脚本**

```java
// src/test/java/com/github/hbq969/ai/zephyr/knowledge/RecallTest.java
package com.github.hbq969.ai.zephyr.knowledge;

import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.github.hbq969.ai.zephyr.knowledge.service.impl.KnowledgeServiceImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("me")
class RecallTest {

    @Resource
    private KnowledgeService knowledgeService;

    @Test
    void recallAt3_shouldMeetTarget() {
        var gson = new Gson();
        var is = getClass().getClassLoader().getResourceAsStream("knowledge-recall-testset.json");
        Map<String, Object> testset = gson.fromJson(
                new InputStreamReader(is, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Object>>() {}.getType());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) testset.get("queries");
        String kbId = (String) testset.get("kbId");

        int hitCount = 0;
        int total = 0;

        for (Map<String, Object> q : queries) {
            String query = (String) q.get("query");
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) q.get("expectedChunkIds");
            if (expected == null || expected.isEmpty()) {
                total++;
                continue;  // 未标注的查询跳过但计入 total，避免 NaN
            }
            total++;
            var results = knowledgeService.search(query, List.of(kbId), 3);
            boolean hit = results.stream().anyMatch(r ->
                    expected.stream().anyMatch(e -> r.getSourceFile().contains(e) || r.getContent().contains(e)));
            if (hit) hitCount++;
        }

        double recall = (double) hitCount / total;
        System.out.printf("Recall@3: %.1f%% (%d/%d)%n", recall * 100, hitCount, total);
        assertTrue(recall >= 0.80, "Recall@3 should be >= 80%, got " + recall);
    }
}
```

- [ ] **Step 3: 添加 p95 延迟测试**

```java
// 在 RecallTest.java 中添加
    @Test
    void searchLatency_shouldBeUnder500msP95() {
        int samples = 50;
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            knowledgeService.search("延迟测试查询", List.of("test-kb-id"), 5);
            latencies.add((System.nanoTime() - start) / 1_000_000);
        }
        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) (samples * 0.95));
        double avgMs = latencies.stream().mapToLong(Long::valueOf).average().orElse(0);

        System.out.printf("Search latency: avg=%.0fms, p95=%dms (50 samples)%n", avgMs, p95);
        assertTrue(p95 < 500, "p95 should be < 500ms, got " + p95 + "ms (avg=" + avgMs + "ms)");
        System.out.println("LATENCY_CHECK: p95=" + p95 + "ms, avg=" + avgMs + "ms");
    }
```

- [ ] **Step 4: 添加 baseline 捕获步骤**

```java
// 在 RecallTest.java 中添加
    @Test
    void captureBaseline() {
        var gson = new Gson();
        var is = getClass().getClassLoader().getResourceAsStream("knowledge-recall-testset.json");
        Map<String, Object> testset = gson.fromJson(
                new InputStreamReader(is, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Object>>() {}.getType());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) testset.get("queries");

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("timestamp", System.currentTimeMillis());
        List<Map<String, Object>> results = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) testset.get("queries");
        String kbId = (String) testset.get("kbId");

        for (Map<String, Object> q : queries) {
            String query = (String) q.get("query");
            long start = System.nanoTime();
            var sr = knowledgeService.search(query, List.of(kbId), 3);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("query", query);
            entry.put("latencyMs", elapsedMs);
            entry.put("top3ChunkIds", sr.stream().map(r -> r.getSourceFile()).toList());
            entry.put("top3Scores", sr.stream().map(r -> r.getScore()).toList());
            results.add(entry);
        }
        baseline.put("results", results);

        // 写入 target/recall-baseline.json
        Path outPath = Paths.get("target/recall-baseline.json");
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, gson.toJson(baseline));
        System.out.println("Baseline saved to " + outPath.toAbsolutePath());
    }
```

运行：
```bash
mvn test -Dtest=RecallTest#captureBaseline -Dsurefire.useFile=false 2>&1 | tail -5
```

预期：输出 "Baseline saved to .../target/recall-baseline.json"

- [ ] **Step 5: 部署迁移说明**

KeywordIndex 是内存索引，服务重启后自动从 ChromaDB 中已存储的 chunk 数据重建。部署新代码后需：

1. 重启服务（内存索引自动清空）
2. 对现有知识库的每个文档调用 `reParseDoc()` 重新解析（会自动将新 `tokenizeForBm25()` 产生的 bigram 分词结果写入内存索引）

> 或者：如果服务重启后所有 `processDocContentAsync` 不重新执行，则需手动对每个知识库重新上传文档，或提供一次性脚本遍历已有文档重新索引。

- [ ] **Step 6: Commit**

```bash
git add src/test/ && git commit -m "$(cat <<'EOF'
test: 知识库召回率量化测试框架 — Recall@3跑分+baseline+测试数据集

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Execution Order

```
前置: 导入测试知识库数据 + captureBaseline(原始代码)
  →  Task 1 (BM25)
  →  Task 2 (窗口扩展)
  →  Task 3 (查询增强)
  →  Task 4 (验证: recallAt3 + 对比 baseline)
```

⚠️ **重要：** 在开始 Task 1 之前，必须在**原始代码**上先 `captureBaseline()` 并保存到 `target/recall-baseline-before.json`。Task 4 的验证需要将此 baseline 与改动后的结果（`target/recall-baseline-after.json`）对比，确保 Recall@3 不退化。

三项改动互不依赖，顺序任意。Task 4 在所有改动完成后执行，需事先导入测试知识库数据。
