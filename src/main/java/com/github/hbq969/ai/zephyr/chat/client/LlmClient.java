package com.github.hbq969.ai.zephyr.chat.client;

import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.model.ToolDef;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.google.gson.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LlmClient {

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;


    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");



    private OkHttpClient httpClient;

    @PostConstruct
    void initHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(cfg.getLlm().getClient().getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(cfg.getLlm().getClient().getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    private final ConcurrentHashMap<String, okhttp3.Call> activeCalls = new ConcurrentHashMap<>();

    public void cancelCall(String key) {
        okhttp3.Call call = activeCalls.remove(key);
        if (call != null) {
            log.info("取消 LLM API 调用: {}", key);
            call.cancel();
        }
    }

    public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                          List<ToolDef> tools, SseEmitter emitter, String conversationId) throws IOException {
        String apiKey = AESUtil.decrypt(model.getApiKeyEncrypted(), cfg.getEncrypt().getRestful().getAes().getKey(), cfg.getEncrypt().getRestful().getAes().getIv(), StandardCharsets.UTF_8);
        String baseUrl = model.getBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model.getName());
        bodyJson.addProperty("stream", true);
        bodyJson.add("messages", gson.toJsonTree(messages));

        if (tools != null && !tools.isEmpty()) {
            bodyJson.add("tools", gson.toJsonTree(tools));
        }

        // 注入模型参数（temperature、top_p、max_tokens 等 + 点号路径展开为嵌套对象）
        Map<String, Object> params = parseParams(model.getParams());
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if ("request_timeout".equals(e.getKey())) continue;
                if (e.getKey().contains(".")) {
                    setNestedProperty(bodyJson, e.getKey(), e.getValue());
                } else {
                    addJsonProperty(bodyJson, e.getKey(), e.getValue());
                }
            }
        }

        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        bodyJson.add("stream_options", streamOpts);

        int timeout = getTimeoutSeconds(params);
        RequestBody reqBody = RequestBody.create(gson.toJson(bodyJson), JSON);
        OkHttpClient client = (timeout != 120)
                ? httpClient.newBuilder().readTimeout(timeout, TimeUnit.SECONDS).build()
                : httpClient;
        Request request = new Request.Builder()
                .url(baseUrl + "v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(reqBody)
                .build();

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<LlmResult.ToolCall> toolCalls = new ArrayList<>();
        JsonArray accumulatedToolCalls = new JsonArray();
        Map<String, Integer> usageResult = new LinkedHashMap<>();

        String bodyStr = gson.toJson(bodyJson);
        log.info("请求模型 {}，消息数: {}，上下文约 {} 字符", model.getName(), messages.size(), bodyStr.length());

        okhttp3.Call call = client.newCall(request);
        activeCalls.put(conversationId, call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                int code = response.code();
                String errorBody = "";
                try {
                    if (response.body() != null) errorBody = response.body().string();
                } catch (Exception ignored) {}
                log.warn("LLM API 调用失败: {} {} - {}", baseUrl + "v1/chat/completions", code, errorBody);
                String errMsg = String.format("API 错误 [%d]: %s", code,
                        errorBody.isEmpty() ? (code == 401 ? "API Key 无效" : code == 404 ? "接口不存在，请检查 Base URL" : "请求失败") : errorBody);
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("error").content(errMsg).build()));
                return LlmResult.builder().content("").build();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    try {
                        JsonObject event = gson.fromJson(data, JsonObject.class);
                        if (event.has("choices") && event.getAsJsonArray("choices").size() > 0) {
                            JsonElement choiceEl = event.getAsJsonArray("choices").get(0);
                            if (!choiceEl.isJsonObject()) continue;
                            JsonObject choice = choiceEl.getAsJsonObject();
                            JsonObject delta = (choice.has("delta") && !choice.get("delta").isJsonNull())
                                    ? choice.getAsJsonObject("delta") : null;
                            if (delta == null) continue;

                            // text content
                            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                String token = delta.get("content").getAsString();
                                fullContent.append(token);
                                emitter.send(SseEmitter.event().name("message")
                                        .data(ChatEvent.builder().type("token").content(token).build()));
                            }

                            // thinking (DeepSeek)
                            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                String thinking = delta.get("reasoning_content").getAsString();
                                fullThinking.append(thinking);
                                emitter.send(SseEmitter.event().name("message")
                                        .data(ChatEvent.builder().type("thinking").content(thinking).build()));
                            }

                            // tool calls
                            if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                                JsonArray tcArray = delta.getAsJsonArray("tool_calls");
                                Set<String> emittedNames = new HashSet<>();
                                for (int i = 0; i < tcArray.size(); i++) {
                                    JsonObject tc = tcArray.get(i).getAsJsonObject();
                                    int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
                                    while (accumulatedToolCalls.size() <= idx) {
                                        accumulatedToolCalls.add(new JsonObject());
                                    }
                                    JsonObject accumulated = accumulatedToolCalls.get(idx).getAsJsonObject();

                                    if (tc.has("id")) accumulated.addProperty("id", tc.get("id").getAsString());
                                    if (tc.has("function")) {
                                        JsonObject func = tc.getAsJsonObject("function");
                                        if (!accumulated.has("function")) accumulated.add("function", new JsonObject());
                                        JsonObject accFunc = accumulated.getAsJsonObject("function");
                                        if (func.has("name")) {
                                            String name = func.get("name").getAsString();
                                            accFunc.addProperty("name", name);
                                            // 首次解析到 name 时推送 tool_call 事件
                                            if (emittedNames.add(String.valueOf(idx) + ":" + name)) {
                                                try {
                                                    emitter.send(SseEmitter.event().name("message")
                                                            .data(ChatEvent.builder()
                                                                    .type("tool_call")
                                                                    .toolName(name)
                                                                    .build()));
                                                } catch (IOException e) {
                                                    log.warn("推送 tool_call 事件失败: {}", e.getMessage());
                                                }
                                            }
                                        }
                                        if (func.has("arguments")) {
                                            String args = accFunc.has("arguments") ? accFunc.get("arguments").getAsString() : "";
                                            accFunc.addProperty("arguments", args + func.get("arguments").getAsString());
                                        }
                                    }
                                }
                            }

                            // finish reason
                            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                                String finishReason = choice.get("finish_reason").getAsString();
                                if ("tool_calls".equals(finishReason)) {
                                    for (int i = 0; i < accumulatedToolCalls.size(); i++) {
                                        JsonObject tc = accumulatedToolCalls.get(i).getAsJsonObject();
                                        JsonObject func = tc.getAsJsonObject("function");
                                        LlmResult.ToolCall toolCall = LlmResult.ToolCall.builder()
                                                .id(tc.has("id") ? tc.get("id").getAsString() : "")
                                                .name(func.get("name").getAsString())
                                                .arguments(gson.fromJson(func.get("arguments").getAsString(),
                                                        new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType()))
                                                .build();
                                        toolCalls.add(toolCall);
                                    }
                                }
                            }
                        }

                        // usage
                        if (event.has("usage") && !event.get("usage").isJsonNull()) {
                            JsonObject usage = event.getAsJsonObject("usage");
                            if (usage.has("prompt_tokens")) usageResult.put("inputTokens", usage.get("prompt_tokens").getAsInt());
                            if (usage.has("completion_tokens")) usageResult.put("outputTokens", usage.get("completion_tokens").getAsInt());
                            emitter.send(SseEmitter.event().name("message")
                                    .data(ChatEvent.builder().type("usage").usage(usageResult).build()));
                        }
                    } catch (com.google.gson.JsonSyntaxException e) {
                        log.warn("解析 SSE 事件失败: {}", e.getMessage());
                    }
                }
            }
            } catch (IOException e) {
                // cancelCall 触发的是预期行为，不抛异常
                if (!activeCalls.containsKey(conversationId)) {
                    log.debug("LLM 调用已被取消: {}", conversationId);
                } else {
                    throw e;
                }
            }
        } finally {
            activeCalls.remove(conversationId, call);
        }

        if (!usageResult.isEmpty()) {
            log.info("模型 {} 返回 — 输入: {} tokens, 输出: {} tokens", model.getName(), usageResult.getOrDefault("inputTokens", 0), usageResult.getOrDefault("outputTokens", 0));
        } else {
            log.info("模型 {} 返回 — 内容长度: {} 字符", model.getName(), fullContent.length());
        }

        return LlmResult.builder()
                .content(fullContent.toString())
                .thinking(fullThinking.length() > 0 ? fullThinking.toString() : null)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .usage(usageResult.isEmpty() ? null : usageResult)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return null;
        try {
            return gson.fromJson(paramsJson, Map.class);
        } catch (Exception e) {
            log.warn("解析模型参数失败: {}", e.getMessage());
            return null;
        }
    }

    private int getTimeoutSeconds(Map<String, Object> params) {
        if (params == null) return 120;
        Object v = params.get("request_timeout");
        if (v instanceof Number) {
            int t = ((Number) v).intValue();
            return t > 0 ? t : 120;
        }
        return 120;
    }

    private void setNestedProperty(JsonObject root, String path, Object value) {
        String[] parts = path.split("\\.");
        JsonObject cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = cur.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                JsonObject next = new JsonObject();
                cur.add(parts[i], next);
                cur = next;
            } else {
                cur = child.getAsJsonObject();
            }
        }
        addJsonProperty(cur, parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private void addJsonProperty(JsonObject obj, String key, Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && d <= Long.MAX_VALUE) {
                obj.addProperty(key, (long) d);
            } else {
                obj.addProperty(key, d);
            }
        } else if (value instanceof Boolean) {
            obj.addProperty(key, (Boolean) value);
        } else if (value instanceof Map) {
            obj.add(key, gson.toJsonTree(value));
        } else {
            obj.addProperty(key, String.valueOf(value));
        }
    }
}
