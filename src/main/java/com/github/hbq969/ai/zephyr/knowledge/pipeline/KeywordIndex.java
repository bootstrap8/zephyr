package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

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

            Set<String> chunkTerms = tokenizeForBm25(text != null ? text : "");
            for (String term : chunkTerms) {
                Map<String, Integer> chunkMap = kbTermChunk.get(term);
                if (chunkMap != null) {
                    chunkMap.remove(chunkId);
                    if (chunkMap.isEmpty()) {
                        kbTermChunk.remove(term);
                    }
                }
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

    public synchronized Map<String, Float> search(String query, List<String> kbIds, int topK) {
        Set<String> queryTerms = tokenizeForBm25(query);
        if (queryTerms.isEmpty()) return new LinkedHashMap<>();

        double k1 = cfg != null ? cfg.getKnowledge().getBm25().getK1() : 1.5;
        double b = cfg != null ? cfg.getKnowledge().getBm25().getB() : 0.75;

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
                        (v1, v2) -> v1, LinkedHashMap::new));
    }

    /**
     * 获取指定 chunk 前后各 window 个相邻 chunk 的 chunkId 列表（含自身）。
     * 自动通过 chunkKbMap 定位所属 KB（O(1)）。
     * 越界的 chunkIndex 静默跳过。
     */
    public synchronized List<String> expandWindow(String chunkId, int window) {
        String kbId = chunkKbMap.get(chunkId);
        if (kbId == null) return List.of(chunkId);
        Map<String, String> kbTexts = chunkTexts.get(kbId);
        if (kbTexts == null) return List.of(chunkId);

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

    public synchronized String getChunkText(String chunkId) {
        return textById.get(chunkId);
    }

    /**
     * BM25 专用分词：中文 bigram（过滤单字），英文按空格拆词取 >=2 字符的 token。
     */
    private Set<String> tokenizeForBm25(String text) {
        Set<String> terms = new HashSet<>();
        for (String w : text.split("\\s+")) {
            w = w.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (w.length() >= 2) terms.add(w);
        }
        String cn = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cn.length() - 1; i++) {
            terms.add(cn.substring(i, i + 2));
        }
        return terms;
    }
}
