package com.github.hbq969.ai.zephyr.config.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ModelConfigServiceImpl implements ModelConfigService {

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;



    @Resource
    private ModelConfigDao modelConfigDao;

    @Resource
    private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;

    @Override
    public List<ModelConfigEntity> list(String userName) {
        List<ModelConfigEntity> shared = modelConfigDao.queryShared();
        List<ModelConfigEntity> own = modelConfigDao.queryByUserName(userName);

        Map<String, ModelConfigEntity> dedup = new LinkedHashMap<>();
        for (ModelConfigEntity e : shared) {
            dedup.put(e.getName(), e);
        }
        for (ModelConfigEntity e : own) {
            dedup.put(e.getName(), e);
        }

        com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity llmPref =
                userModelPreferenceDao.queryByUserAndType(userName, "llm");
        com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity embPref =
                userModelPreferenceDao.queryByUserAndType(userName, "embedding");

        List<ModelConfigEntity> result = new ArrayList<>(dedup.values());
        for (ModelConfigEntity e : result) {
            String key = e.getApiKeyEncrypted();
            if (key != null && !key.isEmpty()) {
                e.setApiKeyEncrypted(maskApiKey(key));
            }
            String mt = e.getModelType() != null ? e.getModelType() : "llm";
            if ("llm".equals(mt) && llmPref != null && llmPref.getModelId().equals(e.getId())) {
                e.setIsDefault(1);
            } else if ("embedding".equals(mt) && embPref != null && embPref.getModelId().equals(e.getId())) {
                e.setIsDefault(1);
            }
        }
        return result;
    }

    @Override
    public List<ModelConfigEntity> listByType(String modelType, String userName) {
        List<ModelConfigEntity> all = list(userName);
        List<ModelConfigEntity> filtered = new ArrayList<>();
        for (ModelConfigEntity e : all) {
            String mt = e.getModelType() != null ? e.getModelType() : "llm";
            if (modelType.equals(mt)) {
                filtered.add(e);
            }
        }
        return filtered;
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
        String maxCtx = body.get("maxContextTokens");
        if (maxCtx != null && !maxCtx.isBlank()) {
            entity.setMaxContextTokens(Long.parseLong(maxCtx));
        }
        String params = body.get("params");
        entity.setParams(params != null && !params.isBlank() ? params : null);
        entity.setModelType(body.getOrDefault("modelType", "llm"));
        String dimensions = body.get("dimensions");
        if (dimensions != null && !dimensions.isBlank()) {
            entity.setDimensions(Integer.parseInt(dimensions));
        }
        entity.setIsDefault(0);
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public Long detectContext(Map<String, String> body, String userName) {
        String id = body.get("id");
        String name = body.get("name");
        String baseUrl = body.get("baseUrl");
        String apiKey = body.get("apiKey");

        // 原始参数探测（不存库）
        if (id == null || id.isBlank()) {
            ModelConfigEntity tmp = new ModelConfigEntity();
            tmp.setName(name);
            tmp.setBaseUrl(baseUrl);
            return detectMaxContextTokens(tmp, apiKey);
        }

        // 按 ID 探测并回存
        ModelConfigEntity entity = modelConfigDao.queryById(id);
        if (entity == null) return null;
        Long tokens = detectMaxContextTokens(entity, decryptApiKey(entity.getApiKeyEncrypted()));
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
        String params = body.get("params");
        entity.setParams(params != null ? params : null);
        String modelType = body.get("modelType");
        if (modelType != null && !modelType.isBlank()) {
            entity.setModelType(modelType);
        }
        String dimensions = body.get("dimensions");
        if (dimensions != null && !dimensions.isBlank()) {
            entity.setDimensions(Integer.parseInt(dimensions));
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
        ModelConfigEntity entity = modelConfigDao.queryById(id);
        if (entity == null) throw new RuntimeException("模型不存在");

        if (!"shared".equals(entity.getScope()) && !userName.equals(entity.getUserName())) {
            throw new RuntimeException("无权访问此模型");
        }

        String modelType = entity.getModelType() != null ? entity.getModelType() : "llm";
        modelConfigDao.clearDefault(userName);

        com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity pref =
                new com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity();
        pref.setId(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        pref.setUserName(userName);
        pref.setModelType(modelType);
        pref.setModelId(id);
        pref.setCreatedAt(System.currentTimeMillis() / 1000);
        pref.setUpdatedAt(System.currentTimeMillis() / 1000);
        userModelPreferenceDao.upsert(pref);
    }

    @Override
    @Transactional
    public void toggleScope(String id, String scope, String userName) {
        com.github.hbq969.code.sm.login.model.UserInfo ui =
                com.github.hbq969.code.sm.login.session.UserContext.getNoCheck();
        if (ui == null || !ui.isAdmin()) throw new RuntimeException("仅 admin 可管理共享模型");

        modelConfigDao.toggleScope(id, scope, System.currentTimeMillis() / 1000);

        if ("user".equals(scope)) {
            userModelPreferenceDao.deleteByModelId(id);
        }
    }

    private String encryptApiKey(String plain) {
        return AESUtil.encrypt(plain, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8);
    }

    private String decryptApiKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        return AESUtil.decrypt(encrypted, cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8);
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    @Override
    public List<Map<String, Object>> fetchModels(Map<String, String> body) {
        String baseUrl = body.get("baseUrl");
        String apiKey = body.get("apiKey");
        String id = body.get("id");

        // 编辑模式：传了模型 id 但没传 apiKey，用已存的 key
        if (id != null && !id.isBlank() && (apiKey == null || apiKey.isBlank())) {
            ModelConfigEntity existing = modelConfigDao.queryById(id);
            if (existing != null) {
                apiKey = decryptApiKey(existing.getApiKeyEncrypted());
                if (baseUrl == null || baseUrl.isBlank()) {
                    baseUrl = existing.getBaseUrl();
                }
            }
        }

        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            String url = baseUrl.replaceAll("/$", "") + "/v1/models";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(cfg.getModelConfig().getApi().getConnectTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(cfg.getModelConfig().getApi().getReadTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();
            okhttp3.Response resp = client.newCall(req).execute();
            if (!resp.isSuccessful()) { resp.close(); return List.of(); }
            String respBody = resp.body() != null ? resp.body().string() : "";
            resp.close();
            com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(respBody, com.google.gson.JsonObject.class);
            List<Map<String, Object>> models = new ArrayList<>();
            if (json.has("data")) {
                for (var item : json.getAsJsonArray("data")) {
                    var obj = item.getAsJsonObject();
                    if (obj.has("id")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", obj.get("id").getAsString());
                        models.add(m);
                    }
                }
            }
            return models;
        } catch (Exception e) {
            log.info("拉取模型列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Long detectMaxContextTokens(ModelConfigEntity entity, String apiKey) {
        if (entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) return null;
        try {
            String url = entity.getBaseUrl().replaceAll("/$", "") + "/v1/models";
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(cfg.getModelConfig().getApi().getConnectTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(cfg.getModelConfig().getApi().getReadTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
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
