package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ChromaClient implements InitializingBean {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private String baseUrl;

    @Override
    public void afterPropertiesSet() {
        var chromaCfg = cfg.getKnowledge().getChroma();
        if ("embedded".equals(chromaCfg.getMode())) {
            startEmbeddedChroma();
            this.baseUrl = "http://localhost:" + chromaCfg.getPort();
        } else {
            this.baseUrl = chromaCfg.getBaseUrl();
        }
        log.info("ChromaClient 初始化完成: baseUrl={}", baseUrl);
    }

    private void startEmbeddedChroma() {
        var chromaCfg = cfg.getKnowledge().getChroma();
        try {
            new ProcessBuilder("chroma", "run", "--path", chromaCfg.getDataDir(),
                    "--port", String.valueOf(chromaCfg.getPort()))
                    .inheritIO()
                    .start();
            Thread.sleep(2000);
            log.info("Embedded Chroma 已启动, path={}, port={}", chromaCfg.getDataDir(), chromaCfg.getPort());
        } catch (Exception e) {
            log.warn("Chroma 子进程启动失败，请确保已安装: pip install chromadb。将尝试连接已有实例。");
        }
    }

    public void createCollection(String collectionName) {
        Map<String, Object> body = Map.of("name", collectionName);
        try {
            post("/api/v1/collections", body);
        } catch (Exception e) {
            log.warn("创建 Chroma collection 失败（可能已存在）: {}", e.getMessage());
        }
    }

    public void deleteCollection(String collectionName) {
        try {
            delete("/api/v1/collections/" + collectionName);
        } catch (Exception e) {
            log.warn("删除 Chroma collection 失败: {}", e.getMessage());
        }
    }

    public void add(String collectionName, List<String> ids, List<float[]> embeddings,
                    List<Map<String, String>> metadatas, List<String> documents) {
        List<List<Float>> embList = new ArrayList<>();
        for (float[] vec : embeddings) {
            List<Float> list = new ArrayList<>();
            for (float v : vec) list.add(v);
            embList.add(list);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("embeddings", embList);
        body.put("metadatas", metadatas);
        body.put("documents", documents);
        post("/api/v1/collections/" + collectionName + "/add", body);
    }

    public List<QueryResult> query(String collectionName, float[] queryEmbedding, int topK) {
        List<Float> qEmb = new ArrayList<>();
        for (float v : queryEmbedding) qEmb.add(v);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(qEmb));
        body.put("n_results", topK);

        String resp = post("/api/v1/collections/" + collectionName + "/query", body);
        Map<String, Object> result = gson.fromJson(resp, new TypeToken<Map<String, Object>>(){}.getType());

        List<QueryResult> results = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<List<String>> idsList = (List<List<String>>) result.get("ids");
        @SuppressWarnings("unchecked")
        List<List<String>> docsList = (List<List<String>>) result.get("documents");
        @SuppressWarnings("unchecked")
        List<List<Double>> distsList = (List<List<Double>>) result.get("distances");
        @SuppressWarnings("unchecked")
        List<List<Map<String, String>>> metasList = (List<List<Map<String, String>>>) result.get("metadatas");

        if (idsList != null && !idsList.isEmpty()) {
            List<String> ids = idsList.get(0);
            List<String> docs = docsList != null && !docsList.isEmpty() ? docsList.get(0) : new ArrayList<>();
            List<Double> dists = distsList != null && !distsList.isEmpty() ? distsList.get(0) : new ArrayList<>();
            List<Map<String, String>> metas = metasList != null && !metasList.isEmpty() ? metasList.get(0) : new ArrayList<>();

            for (int i = 0; i < ids.size(); i++) {
                QueryResult qr = new QueryResult();
                qr.setId(ids.get(i));
                qr.setDocument(i < docs.size() ? docs.get(i) : "");
                qr.setMetadata(i < metas.size() ? metas.get(i) : Map.of());
                qr.setScore(i < dists.size() ? dists.get(i) : 0.0);
                results.add(qr);
            }
        }
        return results;
    }

    private String post(String path, Map<String, Object> body) {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Chroma API error: " + response.code() + " " + errBody);
            }
            return response.body() != null ? response.body().string() : "{}";
        } catch (IOException e) {
            throw new RuntimeException("Chroma 请求失败: " + e.getMessage(), e);
        }
    }

    private String delete(String path) {
        try (Response response = client.newCall(new Request.Builder()
                .url(baseUrl + path).delete().build()).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        } catch (IOException e) {
            throw new RuntimeException("Chroma 删除失败: " + e.getMessage(), e);
        }
    }

    @Data
    public static class QueryResult {
        private String id;
        private String document;
        private Map<String, String> metadata;
        private double score;
    }
}
