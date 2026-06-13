# 知识库向量检索召回率优化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改进知识库检索管道——分块尊重文档结构、增加关键词索引双路检索、RRF 融合排序——提升 API 文档等半结构化场景的召回准确率

**Architecture:** 3 个组件改动：TextSplitter 分隔符重排 + 章节前缀；新增 KeywordIndex（内存倒排索引）；新增 RrfMerger（Reciprocal Rank Fusion）。KnowledgeServiceImpl.search() 串联三者为双路检索。无需改动数据库 DDL。

**Tech Stack:** Java 17, SpringBoot 3.5.4, OkHttp, Chroma (向量库)

---

### Task 1: 重写 TextSplitter — 分隔符优先级 + 章节前缀

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextSplitter.java`

- [ ] **Step 1: 完整替换 TextSplitter.java**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextSplitter {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,4}\\s+.+$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");

    // 分隔符优先级：双换行 > 中文句号 > 中文感叹号 > 中文问号 > 单换行
    private static final List<String> SEPARATORS = List.of("\n\n", "。", "！", "？", "\n");

    private final int chunkSize;
    private final int overlap;
    private final int minChunkSize;

    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minChunkSize = Math.max(chunkSize / 4, 100);
    }

    public TextSplitter() {
        this(800, 150);
    }

    public List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        List<Heading> headings = extractHeadings(text);

        Map<String, String> placeholders = new HashMap<>();
        text = protectCodeBlocks(text, placeholders);

        List<String> rawChunks = new ArrayList<>();
        splitRecursive(text, rawChunks);

        for (String chunk : rawChunks) {
            String restored = restorePlaceholders(chunk, placeholders);
            String heading = findNearestHeading(headings, text, chunk);
            if (heading != null && !heading.isEmpty()) {
                result.add(heading + "\n" + restored);
            } else {
                result.add(restored);
            }
        }
        return result;
    }

    private void splitRecursive(String text, List<String> chunks) {
        if (text.length() <= chunkSize) {
            if (!text.trim().isEmpty()) chunks.add(text.trim());
            return;
        }
        String sep = findSeparator(text);
        if (sep == null) {
            hardSplit(text, chunks);
            return;
        }
        String[] parts = text.split(Pattern.quote(sep), -1);
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() > 0 ? current + sep + part : part;
            if (candidate.length() > chunkSize && current.length() >= minChunkSize) {
                chunks.add(current.toString().trim());
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

    private void hardSplit(String text, List<String> chunks) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end).trim());
            start = end - overlap;
        }
    }

    private String findSeparator(String text) {
        for (String sep : SEPARATORS) {
            if (text.contains(sep)) return sep;
        }
        return null;
    }

    private List<Heading> extractHeadings(String text) {
        List<Heading> result = new ArrayList<>();
        Matcher m = MARKDOWN_HEADING.matcher(text);
        while (m.find()) result.add(new Heading(m.start(), m.group().trim()));
        return result;
    }

    private String findNearestHeading(List<Heading> headings, String fullText, String chunk) {
        if (headings.isEmpty()) return null;
        int pos = fullText.indexOf(chunk.substring(0, Math.min(50, chunk.length())));
        if (pos < 0) return null;
        Heading nearest = null;
        for (Heading h : headings) {
            if (h.pos <= pos) nearest = h;
            else break;
        }
        return nearest != null ? nearest.text : null;
    }

    private String protectCodeBlocks(String text, Map<String, String> placeholders) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String key = "%%CB" + idx + "%%";
            placeholders.put(key, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(key));
            idx++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String restorePlaceholders(String text, Map<String, String> placeholders) {
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    private static class Heading {
        final int pos;
        final String text;
        Heading(int pos, String text) { this.pos = pos; this.text = text; }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/TextSplitter.java
git commit -m "refactor: TextSplitter 分隔符优先级重排，增加 Markdown 标题前缀拼接"
```

---

### Task 2: 新增 KeywordIndex — 内存关键词倒排索引

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java`

- [ ] **Step 1: 创建 KeywordIndex.java**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class KeywordIndex {

    // kbId -> (term -> Set<chunkId>)
    private final Map<String, Map<String, Set<String>>> idx = new HashMap<>();
    // kbId -> (chunkId -> chunkText)
    private final Map<String, Map<String, String>> texts = new HashMap<>();

    public synchronized void addChunks(String kbId, String docId, List<String> chunks) {
        idx.computeIfAbsent(kbId, k -> new HashMap<>());
        texts.computeIfAbsent(kbId, k -> new HashMap<>());

        Map<String, Set<String>> kbIdx = idx.get(kbId);
        Map<String, String> kbTexts = texts.get(kbId);

        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "_" + i;
            kbTexts.put(chunkId, chunks.get(i));
            for (String term : tokenize(chunks.get(i))) {
                kbIdx.computeIfAbsent(term, k -> new HashSet<>()).add(chunkId);
            }
        }
    }

    public synchronized void removeDoc(String kbId, String docId) {
        Map<String, Set<String>> kbIdx = idx.get(kbId);
        Map<String, String> kbTexts = texts.get(kbId);
        if (kbIdx == null || kbTexts == null) return;

        List<String> toRemove = new ArrayList<>();
        for (String chunkId : kbTexts.keySet()) {
            if (chunkId.startsWith(docId + "_")) toRemove.add(chunkId);
        }
        for (String chunkId : toRemove) {
            kbTexts.remove(chunkId);
            for (Set<String> s : kbIdx.values()) s.remove(chunkId);
        }
        log.info("关键词索引已移除文档: kbId={}, docId={}, chunks={}", kbId, docId, toRemove.size());
    }

    public synchronized void removeKb(String kbId) {
        idx.remove(kbId);
        texts.remove(kbId);
    }

    public synchronized Map<String, Float> search(String query, List<String> kbIds, int topK) {
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return new LinkedHashMap<>();

        Map<String, Float> scores = new HashMap<>();

        for (String kbId : kbIds) {
            Map<String, Set<String>> kbIdx = idx.get(kbId);
            Map<String, String> kbTexts = texts.get(kbId);
            if (kbIdx == null || kbTexts == null) continue;

            for (String term : queryTerms) {
                Set<String> matched = kbIdx.get(term);
                if (matched == null) continue;
                for (String chunkId : matched) {
                    String chunkText = kbTexts.get(chunkId);
                    if (chunkText == null) continue;
                    float tf = termFrequency(chunkText, term);
                    scores.merge(chunkId, tf, Float::sum);
                }
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public synchronized String getChunkText(String chunkId) {
        for (Map<String, String> m : texts.values()) {
            String t = m.get(chunkId);
            if (t != null) return t;
        }
        return null;
    }

    private Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        for (String w : text.split("\\s+")) {
            w = w.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (w.length() >= 2) terms.add(w);
        }
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cn.length() - 1; i++) {
            terms.add(cn.substring(i, i + 2));
        }
        for (int i = 0; i < cn.length(); i++) {
            terms.add(cn.substring(i, i + 1));
        }
        terms.add(text.trim().toLowerCase());
        return terms;
    }

    private float termFrequency(String text, String term) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return (float) count / (float) Math.max(text.length(), 1);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -DskipTests
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/KeywordIndex.java
git commit -m "feat: 新增 KeywordIndex 内存关键词倒排索引"
```

---

### Task 3: 新增 RrfMerger — RRF 融合排序

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/RrfMerger.java`

- [ ] **Step 1: 创建 RrfMerger.java**

```java
package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import java.util.*;

public class RrfMerger {

    private final int k;

    public RrfMerger(int k) {
        this.k = k;
    }

    public RrfMerger() {
        this(60);
    }

    public List<String> merge(List<ChromaClient.QueryResult> vecResults,
                               Map<String, Float> kwResults, int topK) {
        Map<String, Double> rrf = new LinkedHashMap<>();

        for (int i = 0; i < vecResults.size(); i++) {
            rrf.merge(vecResults.get(i).getId(), 1.0 / (k + i + 1), Double::sum);
        }

        int rank = 0;
        for (String chunkId : kwResults.keySet()) {
            rank++;
            rrf.merge(chunkId, 1.0 / (k + rank), Double::sum);
        }

        return rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -DskipTests
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/pipeline/RrfMerger.java
git commit -m "feat: 新增 RrfMerger 双路检索融合排序"
```

---

### Task 4: 修改 KnowledgeServiceImpl — 双路检索 + 索引生命周期

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java`

- [ ] **Step 1: 注入 KeywordIndex**

在 `@Resource private ChromaClient chromaClient;`（第 49 行）后追加：

```java
@Resource
private KeywordIndex keywordIndex;
```

- [ ] **Step 2: processDocAsync() — 写入关键词索引**

在 `chromaClient.add(collId, ids, embeddings, metadatas, chunks);`（第 257 行）后追加：

```java
keywordIndex.addChunks(kbId, docId, chunks);
```

- [ ] **Step 3: deleteDoc() — 级联清理关键词索引**

将原 `deleteDoc()` 方法（第 122-124 行）替换为：

```java
@Override
public void deleteDoc(String id) {
    KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);
    if (doc != null) {
        keywordIndex.removeDoc(doc.getKbId(), id);
    }
    knowledgeDao.deleteDoc(id);
}
```

- [ ] **Step 4: deleteKb() — 级联清理关键词索引**

在 `knowledgeDao.deleteConversationKbByKbId(id);`（第 111 行）后追加：

```java
keywordIndex.removeKb(id);
```

- [ ] **Step 5: 重构 search() 方法**

将原 `search()` 方法（第 180-222 行）完整替换为：

```java
@Override
public List<SearchResult> search(String query, List<String> kbIds, int topK) {
    if (kbIds == null || kbIds.isEmpty()) return List.of();

    Map<String, List<String>> kbByModel = new LinkedHashMap<>();
    for (String kbId : kbIds) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null || kb.getEmbedModelId() == null) {
            log.warn("知识库 {} 未配置 Embedding 模型，跳过", kbId);
            continue;
        }
        kbByModel.computeIfAbsent(kb.getEmbedModelId(), k -> new ArrayList<>()).add(kbId);
    }
    if (kbByModel.isEmpty()) throw new RuntimeException("所选知识库均未配置 Embedding 模型");

    int fetchSize = topK * 2;
    List<ChromaClient.QueryResult> allVecResults = new ArrayList<>();

    for (Map.Entry<String, List<String>> entry : kbByModel.entrySet()) {
        ModelConfigEntity embedModel = modelConfigDao.queryById(entry.getKey());
        if (embedModel == null) {
            log.warn("Embedding 模型 {} 不存在，跳过", entry.getKey());
            continue;
        }
        List<float[]> embeddings = embeddingClient.embed(List.of(query), embedModel);
        if (embeddings.isEmpty()) continue;
        for (String kbId : entry.getValue()) {
            try {
                String collId = chromaClient.getOrCreateCollection("kb_" + kbId);
                allVecResults.addAll(chromaClient.query(collId, embeddings.get(0), fetchSize));
            } catch (Exception e) {
                log.warn("知识库 {} 向量检索失败: {}", kbId, e.getMessage());
            }
        }
    }

    Map<String, Float> kwResults = keywordIndex.search(query, kbIds, fetchSize);

    allVecResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

    RrfMerger merger = new RrfMerger(60);
    List<String> mergedIds = merger.merge(allVecResults, kwResults, topK);

    Map<String, ChromaClient.QueryResult> vecMap = new HashMap<>();
    for (ChromaClient.QueryResult r : allVecResults) {
        vecMap.put(r.getId(), r);
    }

    List<SearchResult> results = new ArrayList<>();
    for (String chunkId : mergedIds) {
        ChromaClient.QueryResult vr = vecMap.get(chunkId);
        if (vr != null) {
            results.add(new SearchResult(vr.getDocument(),
                    vr.getMetadata() != null ? vr.getMetadata().getOrDefault("file_name", "") : "",
                    vr.getScore()));
        } else {
            String text = keywordIndex.getChunkText(chunkId);
            if (text != null) {
                results.add(new SearchResult(text, "关键词匹配", 0.0));
            }
        }
    }
    return results;
}
```

- [ ] **Step 6: 编译验证**

```bash
mvn compile -DskipTests
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/knowledge/service/impl/KnowledgeServiceImpl.java
git commit -m "feat: search() 双路检索（向量 + 关键词）+ RRF 融合排序"
```

---

### Task 5: 端到端验证

- [ ] **Step 1: 打包并重启后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: 确认服务启动**

```bash
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/knowledge/kb/list"
```

- [ ] **Step 3: 重新解析已有文档（触发新的分块 + 索引逻辑）**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/knowledge/doc/re-parse" \
  -d '{"id":"<docId>","kbId":"<kbId>"}'
```

- [ ] **Step 4: 验证召回效果**

对比重新解析前后同一个问题的 search 结果，确认：
- 关键词匹配到的 chunk 出现在结果中
- RRF 让双路都匹配的 chunk 排位更靠前
- 日志中 `KeywordIndex` 正常输出 chunk 数量
