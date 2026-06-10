package com.github.hbq969.ai.zephyr.config.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ModelConfigServiceImpl implements ModelConfigService {

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    @Resource
    private ModelConfigDao modelConfigDao;

    @Override
    public List<ModelConfigEntity> list(String userName) {
        List<ModelConfigEntity> list = modelConfigDao.queryByUserName(userName);
        for (ModelConfigEntity e : list) {
            String key = e.getApiKeyEncrypted();
            if (key != null && !key.isEmpty()) {
                e.setApiKeyEncrypted(maskApiKey(key));
            }
        }
        return list;
    }

    @Override
    @Transactional
    public ModelConfigEntity create(Map<String, String> body, String userName) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
        String apiKey = body.get("apiKey");
        entity.setApiKeyEncrypted(apiKey != null && !apiKey.isEmpty() ? encryptApiKey(apiKey) : "");
        entity.setIsDefault(0);
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public Long detectContext(String id, String userName) {
        ModelConfigEntity entity = modelConfigDao.queryById(id);
        if (entity == null) return null;
        String apiKey = entity.getApiKeyEncrypted();
        Long tokens = detectMaxContextTokens(entity, apiKey);
        if (tokens != null) {
            modelConfigDao.updateMaxContextTokens(id, tokens, System.currentTimeMillis() / 1000, userName);
        }
        return tokens;
    }

    @Override
    @Transactional
    public void update(Map<String, String> body, String userName) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(body.get("id"));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
        String apiKey = body.get("apiKey");
        if (apiKey != null && !apiKey.isEmpty()) {
            entity.setApiKeyEncrypted(encryptApiKey(apiKey));
        }
        String maxCtx = body.get("maxContextTokens");
        if (maxCtx != null && !maxCtx.isBlank()) {
            entity.setMaxContextTokens(Long.parseLong(maxCtx));
        }
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.update(entity);
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        modelConfigDao.delete(id, userName);
    }

    @Override
    @Transactional
    public void setDefault(String id, String userName) {
        modelConfigDao.clearDefault(userName);
        modelConfigDao.setDefault(id, userName);
    }

    private String encryptApiKey(String plain) {
        return AESUtil.encrypt(plain, aesKey, aesIv, StandardCharsets.UTF_8);
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    private Long detectMaxContextTokens(ModelConfigEntity entity, String apiKey) {
        if (entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) return null;
        try {
            String url = entity.getBaseUrl().replaceAll("/$", "") + "/v1/models";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();
            okhttp3.Response resp = client.newCall(req).execute();
            if (!resp.isSuccessful()) { resp.close(); return null; }
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject.class);
            if (json.has("data")) {
                for (var item : json.getAsJsonArray("data")) {
                    var obj = item.getAsJsonObject();
                    String id = obj.has("id") ? obj.get("id").getAsString() : "";
                    if (id.equals(entity.getName()) || id.contains(entity.getName())) {
                        if (obj.has("context_window")) return obj.get("context_window").getAsLong();
                        if (obj.has("max_context_length")) return obj.get("max_context_length").getAsLong();
                        if (obj.has("max_input_tokens")) return obj.get("max_input_tokens").getAsLong();
                        // Some APIs return context_window as a nested field
                        if (obj.has("capabilities")) {
                            var caps = obj.getAsJsonObject("capabilities");
                            if (caps.has("context_window")) return caps.get("context_window").getAsLong();
                        }
                    }
                }
                // 如果没匹配到具体的模型名，尝试取第一个模型的上下文大小
                var first = json.getAsJsonArray("data").get(0).getAsJsonObject();
                if (first.has("context_window")) return first.get("context_window").getAsLong();
                if (first.has("max_context_length")) return first.get("max_context_length").getAsLong();
                if (first.has("max_input_tokens")) return first.get("max_input_tokens").getAsLong();
            }
        } catch (Exception e) {
            log.info("自动探测模型 {} 上下文大小失败: {}", entity.getName(), e.getMessage());
        }
        return null;
    }
}
