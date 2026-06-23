package com.github.hbq969.ai.zephyr.knowledge;

import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库召回率验证测试。
 * <p>
 * 前置条件：需要已导入测试知识库数据（kbId=test-kb-id），且 expectedChunkIds 已手动标注。
 * 首次运行会因 expectedChunkIds 全为空而跳过评估，仅输出 baseline 供标注参考。
 */
@SpringBootTest
@ActiveProfiles("me")
class RecallTest {

    @Resource
    private KnowledgeService knowledgeService;

    private static final Gson gson = new Gson();

    @Test
    void captureBaseline() throws Exception {
        var is = getClass().getClassLoader().getResourceAsStream("knowledge-recall-testset.json");
        if (is == null) {
            System.out.println("测试数据集不存在，跳过 baseline 捕获");
            return;
        }
        Map<String, Object> testset = gson.fromJson(
                new InputStreamReader(is, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Object>>() {}.getType());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queries = (List<Map<String, Object>>) testset.get("queries");
        String kbId = (String) testset.get("kbId");

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("timestamp", System.currentTimeMillis());
        List<Map<String, Object>> results = new ArrayList<>();

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

        Path outPath = Paths.get("target/recall-baseline.json");
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, gson.toJson(baseline));
        System.out.println("Baseline saved to " + outPath.toAbsolutePath());
    }

    @Test
    void recallAt3_shouldMeetTarget() throws Exception {
        var is = getClass().getClassLoader().getResourceAsStream("knowledge-recall-testset.json");
        if (is == null) {
            System.out.println("测试数据集不存在，跳过 Recall@3 评估");
            return;
        }
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
                continue;
            }
            total++;
            var results = knowledgeService.search(query, List.of(kbId), 3);
            boolean hit = results.stream().anyMatch(r ->
                    expected.stream().anyMatch(e -> r.getSourceFile().contains(e) || r.getContent().contains(e)));
            if (hit) hitCount++;
        }

        double recall = (double) hitCount / Math.max(total, 1);
        System.out.printf("Recall@3: %.1f%% (%d/%d)%n", recall * 100, hitCount, total);
        assertTrue(recall >= 0.80, "Recall@3 should be >= 80%, got " + recall);
    }

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
    }
}
