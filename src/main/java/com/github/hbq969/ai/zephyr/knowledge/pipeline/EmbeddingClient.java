package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class EmbeddingClient {

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.get("application/json");

    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public List<float[]> embed(List<String> texts, ModelConfigEntity model) {
        String apiKey = decryptApiKey(model.getApiKeyEncrypted());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getName());
        body.put("input", texts);

        Request request = new Request.Builder()
                .url(model.getBaseUrl() + "/v1/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Embedding API error: " + response.code() + " " + errBody);
            }
            String respBody = response.body().string();
            Map<String, Object> result = gson.fromJson(respBody, new TypeToken<Map<String, Object>>(){}.getType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            data.sort(Comparator.comparingInt(o -> ((Double) o.get("index")).intValue()));

            List<float[]> embeddings = new ArrayList<>();
            for (Map<String, Object> item : data) {
                @SuppressWarnings("unchecked")
                List<Double> emb = (List<Double>) item.get("embedding");
                float[] vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) vec[i] = emb.get(i).floatValue();
                embeddings.add(vec);
            }
            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }

    private String decryptApiKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        var aesCfg = cfg.getEncrypt().getRestful().getAes();
        return AESUtil.decrypt(encrypted, aesCfg.getKey(), aesCfg.getIv(), StandardCharsets.UTF_8);
    }
}
