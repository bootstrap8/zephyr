package com.github.hbq969.ai.zephyr.chat.client;

import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.model.ToolDef;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LlmClient {

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                          List<ToolDef> tools, SseEmitter emitter) throws IOException {
        String apiKey = AESUtil.decrypt(model.getApiKeyEncrypted(), aesKey, aesIv, StandardCharsets.UTF_8);
        String baseUrl = model.getBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model.getName());
        bodyJson.addProperty("stream", true);
        bodyJson.add("messages", gson.toJsonTree(messages));

        if (tools != null && !tools.isEmpty()) {
            bodyJson.add("tools", gson.toJsonTree(tools));
        }

        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        bodyJson.add("stream_options", streamOpts);

        RequestBody body = RequestBody.create(gson.toJson(bodyJson), JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<LlmResult.ToolCall> toolCalls = new ArrayList<>();
        JsonArray accumulatedToolCalls = new JsonArray();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("error").content("API 错误: " + errorBody).build()));
                return LlmResult.builder().content("").build();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    try {
                        JsonObject event = gson.fromJson(data, JsonObject.class);
                        if (event.has("choices") && event.getAsJsonArray("choices").size() > 0) {
                            JsonObject choice = event.getAsJsonArray("choices").get(0).getAsJsonObject();
                            JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : null;
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
                            if (delta.has("tool_calls")) {
                                JsonArray tcArray = delta.getAsJsonArray("tool_calls");
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
                                        if (func.has("name")) accFunc.addProperty("name", func.get("name").getAsString());
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
                        if (event.has("usage")) {
                            JsonObject usage = event.getAsJsonObject("usage");
                            Map<String, Integer> usageMap = new LinkedHashMap<>();
                            if (usage.has("prompt_tokens")) usageMap.put("inputTokens", usage.get("prompt_tokens").getAsInt());
                            if (usage.has("completion_tokens")) usageMap.put("outputTokens", usage.get("completion_tokens").getAsInt());
                            emitter.send(SseEmitter.event().name("message")
                                    .data(ChatEvent.builder().type("usage").usage(usageMap).build()));
                        }
                    } catch (Exception e) {
                        log.warn("解析 SSE 事件失败: {}", e.getMessage());
                    }
                }
            }
        }

        return LlmResult.builder()
                .content(fullContent.toString())
                .thinking(fullThinking.length() > 0 ? fullThinking.toString() : null)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .build();
    }
}
