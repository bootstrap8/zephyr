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

    /**
     * @param vecResults 向量检索结果（已按分数降序排序）
     * @param kwResults  关键词检索结果（chunkId -> score，已按分数降序排序）
     * @param topK       最终返回数量
     * @return 融合后的 chunkId 列表，按 RRF 分数降序
     */
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
