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
