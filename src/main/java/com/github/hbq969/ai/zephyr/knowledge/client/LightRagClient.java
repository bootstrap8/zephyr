package com.github.hbq969.ai.zephyr.knowledge.client;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LightRagClient {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private ZephyrConfigProperties cfg;

    private OkHttpClient client;

    private OkHttpClient client() {
        if (client == null) {
            var lightrag = cfg.getKnowledge().getLightrag();
            client = new OkHttpClient.Builder()
                    .connectTimeout(lightrag.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                    .readTimeout(lightrag.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    private String baseUrl() {
        return cfg.getKnowledge().getLightrag().getBaseUrl();
    }

    private boolean enabled() {
        return cfg.getKnowledge().getLightrag().isEnabled();
    }

    public boolean health() {
        if (!enabled()) return false;
        try {
            String resp = get("/health");
            Map<?, ?> m = gson.fromJson(resp, Map.class);
            return "ok".equals(m.get("status"));
        } catch (Exception e) {
            log.warn("LightRAG health check failed: {}", e.getMessage());
            return false;
        }
    }

    public void index(String kbId, String docId, String text) {
        if (!enabled()) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("doc_id", docId);
            body.put("text", text);
            post("/index/" + kbId, body);
        } catch (Exception e) {
            log.warn("LightRAG index failed (non-fatal): kb={}, doc={}, msg={}", kbId, docId, e.getMessage());
        }
    }

    public List<GraphSearchResult> search(String kbId, String query, String mode, int topK) {
        if (!enabled() || !health()) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("mode", mode);
            body.put("top_k", topK);
            String resp = post("/search/" + kbId, body);
            List<GraphSearchResult> results = gson.fromJson(resp,
                    new TypeToken<List<GraphSearchResult>>() {}.getType());
            return results != null ? results : List.of();
        } catch (Exception e) {
            log.warn("LightRAG search failed (non-fatal): kb={}, msg={}", kbId, e.getMessage());
            return List.of();
        }
    }

    public void deleteDoc(String kbId, String docId) {
        if (!enabled()) return;
        try {
            delete("/index/" + kbId + "/" + docId);
        } catch (Exception e) {
            log.warn("LightRAG deleteDoc failed (non-fatal): kb={}, doc={}, msg={}", kbId, docId, e.getMessage());
        }
    }

    public void deleteKb(String kbId) {
        if (!enabled()) return;
        try {
            delete("/kb/" + kbId);
        } catch (Exception e) {
            log.warn("LightRAG deleteKb failed (non-fatal): kb={}, msg={}", kbId, e.getMessage());
        }
    }

    // --- HTTP helpers ---
    private String get(String path) throws IOException {
        Request request = new Request.Builder().url(baseUrl() + path).get().build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(response.code() + " " + response.message());
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private String post(String path, Map<String, Object> body) throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException(response.code() + " " + err);
            }
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private void delete(String path) throws IOException {
        Request request = new Request.Builder().url(baseUrl() + path).delete().build();
        try (Response response = client().newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException(response.code() + " " + response.message());
        }
    }

    @Data
    public static class GraphSearchResult {
        private String content;
        private String source;
        private double score;
    }
}
