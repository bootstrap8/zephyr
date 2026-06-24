# 会话生命周期管理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 会话退出时自动取消所有关联后台资源（SSE、LLM 调用、异步线程），采用自清理任务 + 薄观察者架构，统一超时 15 分钟。

**Architecture:** 每个异步任务通过 try-finally 管理自己的资源。ConversationSessionManager 是薄观察者（注册/注销 + 超时扫描通知）。合作式取消（volatile boolean），不跨线程 interrupt。conversationId 在 SseEmitter 创建前同步生成。

**Tech Stack:** Java 17, Spring Boot 3.5.4, SseEmitter, OkHttp, ConcurrentHashMap

**Files changed:**
- Create: `ChatServiceImpl` 旁新增 `SessionHandle` 和 `ConversationSessionManager`（新文件）
- Modify: `ChatServiceImpl.java`, `LlmClient.java`, `ConversationServiceImpl.java`, `ChatCtrl.java`, `ZephyrConfigProperties.java`, `application.yml`

---

### Task 1: 配置属性 + application.yml

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java` — Chat 类
- Modify: `src/main/resources/application.yml` — zephyr.chat 段

- [ ] **Step 1: Chat 类新增会话超时配置字段**

在 `ZephyrConfigProperties.Chat` 中，修改 Sse timeoutMillis 默认值，新增 sessionIdleTimeoutSeconds 和 sessionScanIntervalSeconds：

```java
// 在 Chat.Sse 中，修改：
private long timeoutMillis = 900_000L;  // 原来是 300_000L

// 在 Chat 中新增（Sse 类之后）：
/** 会话空闲超时秒数，默认 900（15 分钟），超时后自动标记取消 */
private int sessionIdleTimeoutSeconds = 900;

/** 会话超时扫描间隔秒数，默认 60 */
private int sessionScanIntervalSeconds = 60;
```

即完整 Chat 类变为：

```java
@Data
public static class Chat {

    private int maxHistoryMessages = 200;
    private Upload upload = new Upload();
    private Sse sse = new Sse();
    private ToolOutput toolOutput = new ToolOutput();
    private Context context = new Context();

    /** 会话空闲超时秒数，默认 900（15 分钟），超时后自动标记取消 */
    private int sessionIdleTimeoutSeconds = 900;

    /** 会话超时扫描间隔秒数，默认 60 */
    private int sessionScanIntervalSeconds = 60;

    @Data
    public static class Upload {
        private long maxFileSize = 10_485_760L;
        private String directoryName = ".zephyr-uploads";
    }

    @Data
    public static class Sse {
        /** SSE 连接超时（毫秒），默认 15 分钟（与会话超时对齐） */
        private long timeoutMillis = 900_000L;
    }

    // ... ToolOutput, Context 不变
}
```

- [ ] **Step 2: 更新 application.yml**

将 `zephyr.chat.sse.timeout-millis` 从 300000 改为 900000，并新增会话超时配置：

```yaml
zephyr:
  chat:
    max-history-messages: 200
    upload:
      max-file-size: 209715200
      directory-name: .zephyr-uploads
    sse:
      timeout-millis: 900000  # SSE 连接超时（毫秒），15 分钟，对齐会话超时
    tool-output:
      max-length: 8000
      binary-sample-size: 4096
      binary-threshold: 0.3
      binary-extraction-limit: 6000
    context:
      token-estimation-ratio: 0.3
      skill-token-threshold: 1000
    session-idle-timeout-seconds: 900  # 会话空闲超时（秒），15 分钟无活动自动取消
    session-scan-interval-seconds: 60  # 会话超时扫描间隔（秒）
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java src/main/resources/application.yml
git commit -m "feat: 添加会话超时配置，SSE超时统一为15分钟"
```

---

### Task 2: 新建 ConversationSessionManager

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java`

- [ ] **Step 1: 创建 SessionHandle 和 ConversationSessionManager**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ConversationSessionManager {

    private final ConcurrentHashMap<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @jakarta.annotation.Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @jakarta.annotation.Resource
    private org.springframework.scheduling.TaskScheduler taskScheduler;

    public SessionHandle register(String conversationId, String userName) {
        SessionHandle handle = new SessionHandle(conversationId, userName);
        sessions.put(conversationId, handle);
        log.debug("会话注册: conversationId={}, userName={}", conversationId, userName);
        return handle;
    }

    public SessionHandle get(String conversationId) {
        return sessions.get(conversationId);
    }

    public void remove(String conversationId) {
        sessions.remove(conversationId);
        log.debug("会话注销: conversationId={}", conversationId);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /** 启动定时扫描 */
    @jakarta.annotation.PostConstruct
    void startScan() {
        long intervalMs = cfg.getChat().getSessionScanIntervalSeconds() * 1000L;
        taskScheduler.scheduleAtFixedRate(this::scanExpired,
                java.time.Instant.now().plusMillis(intervalMs),
                java.time.Duration.ofMillis(intervalMs));
    }

    void scanExpired() {
        long now = System.currentTimeMillis() / 1000;
        long idleTimeout = cfg.getChat().getSessionIdleTimeoutSeconds();
        sessions.values().forEach(h -> {
            if (!h.cancelled && now - h.lastActivityTime > idleTimeout) {
                log.info("会话超时取消: conversationId={}, idle={}s",
                        h.conversationId, now - h.lastActivityTime);
                h.cancel();
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("ConversationSessionManager 关闭，取消所有活跃会话");
        sessions.values().forEach(SessionHandle::cancel);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class SessionHandle {
        private final String conversationId;
        private final String userName;
        volatile long lastActivityTime;
        volatile boolean cancelled;

        SessionHandle(String conversationId, String userName) {
            this.conversationId = conversationId;
            this.userName = userName;
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public String getConversationId() { return conversationId; }
        public String getUserName() { return userName; }

        public void touch() {
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public long getLastActivityTime() {
            return lastActivityTime;
        }

        /** 标记取消（幂等，线程安全） */
        public void cancel() {
            this.cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        /** 检查是否被取消，是则抛 CancelSessionException */
        public void checkCancel() {
            if (cancelled) {
                throw new CancelSessionException(conversationId);
            }
        }
    }

    /** 合作式取消异常，由异步任务 catch 后走 finally 清理 */
    public static class CancelSessionException extends RuntimeException {
        public CancelSessionException(String conversationId) {
            super("会话已取消: " + conversationId);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java
git commit -m "feat: 新建ConversationSessionManager，薄观察者+合作式取消"
```

---

### Task 3: LlmClient — cancelKey 改为 userName:conversationId

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java`

- [ ] **Step 1: chat() 签名新增 conversationId 参数，cancelKey 改为 userName:conversationId**

```java
// 修改 chat() 签名，最后一个参数从 String cancelKey 改为 String conversationId
public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                      List<ToolDef> tools, SseEmitter emitter, String conversationId) throws IOException {
    String apiKey = AESUtil.decrypt(model.getApiKeyEncrypted(), ...);
    // ... 前面不变 ...

    String cancelKey = conversationId;  // 原来是参数 cancelKey，现在用 conversationId

    // 其余代码不变（activeCalls.put(cancelKey, call) 等照旧）
```

注意：需要删除原来 `chat()` 方法的 `String cancelKey` 参数，改为用 `String conversationId` 作为 `cancelKey`。

具体改动位置：
1. 方法签名第 57 行：`String cancelKey` → `String conversationId`
2. 第 110 行 `activeCalls.put(cancelKey, call)` 不变（cancelKey 现在由 conversationId 赋值）
3. 第 234-237 行 `activeCalls.containsKey(cancelKey)` 不变
4. 第 243 行 `activeCalls.remove(cancelKey, call)` 不变

实际上最小改动是：删掉 `cancelKey` 参数名，直接用 `conversationId` 替代，所有用到 `cancelKey` 的地方现在用 `conversationId`。

完整修改后的 `chat()` 方法关键部分：

```java
public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                      List<ToolDef> tools, SseEmitter emitter, String conversationId) throws IOException {
    // ... 前面不变（apiKey, baseUrl, bodyJson, params, timeout, request） ...

    okhttp3.Call call = client.newCall(request);
    activeCalls.put(conversationId, call);
    try (Response response = call.execute()) {
        // ... SSE 流读取不变 ...
    } catch (IOException e) {
        if (!activeCalls.containsKey(conversationId)) {
            log.debug("LLM 调用已被取消: {}", conversationId);
        } else {
            throw e;
        }
    } finally {
        activeCalls.remove(conversationId, call);
    }
    // ... return 不变 ...
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS（此时 ChatServiceImpl 的 llmClient.chat() 调用会报编译错误，暂时忽略，下一步修复）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java
git commit -m "refactor: LlmClient cancelKey改为conversationId，支持按会话取消"
```

---

### Task 4: ChatServiceImpl — 自清理 try-finally 重构

这是最核心的改动。将 `send()` 方法重构为：同步阶段（cid 解析 + SessionHandle 注册 + SseEmitter 回调）→ 异步阶段（try-finally 自清理）。

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: 注入 ConversationSessionManager，替换 executor**

```java
// 删除第 41 行：private final ExecutorService executor = Executors.newCachedThreadPool();

// 新增注入（在 @Resource 区域）：
@Resource
private ConversationSessionManager sessionManager;
```

- [ ] **Step 2: 重构 send() 方法 — 同步阶段 + 异步阶段**

完整替换 `send()` 方法（第 67-254 行）：

```java
@Override
public SseEmitter send(String userName, String conversationId, String workspaceId,
                       String originalMessage, String mode, List<String> filePaths) {

    // === 同步阶段：解析 conversationId、注册 SessionHandle、创建 SseEmitter ===
    String cid = (conversationId != null && !conversationId.isEmpty())
            ? conversationId
            : cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12);

    ConversationSessionManager.SessionHandle handle = sessionManager.register(cid, userName);

    SseEmitter emitter = new SseEmitter(cfg.getChat().getSse().getTimeoutMillis());

    emitter.onTimeout(() -> {
        handle.cancel();
        // 不在此处 complete emitter — 由异步任务 finally 统一处理
    });
    emitter.onError(th -> {
        log.warn("SSE client disconnected: {}", th.getMessage());
        handle.cancel();
        // 不在此处 complete emitter — 由异步任务 finally 统一处理
    });

    // === 异步阶段 ===
    sessionManager.getExecutor().execute(() -> {
        boolean completed = false;  // 防止 finally 中二次 complete
        try {
            long now = System.currentTimeMillis() / 1000;
            long msgSeq = now;

            handle.checkCancel();

            // 0. 预处理斜杠命令
            String message = originalMessage;
            if (message.startsWith("/")) {
                String handled = handleSlashCommand(message, userName, cid, emitter, now, handle);
                if (handled == null) return;
                message = handled;
            }

            handle.checkCancel();

            // 1. 确保会话存在（cid 已预生成）
            if (conversationId == null || conversationId.isEmpty()) {
                ConversationEntity conv = new ConversationEntity();
                conv.setId(cid);
                conv.setUserName(userName);
                conv.setTitle(message.length() > 30 ? message.substring(0, 30) : message);
                conv.setWorkspaceId(workspaceId);
                conv.setCreatedAt(now);
                conv.setUpdatedAt(now);
                chatDao.insertConversation(conv);
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("meta").content(cid).build()));
            }

            handle.checkCancel();

            // 2. 组装上下文
            ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode != null ? mode : "default");
            List<Map<String, Object>> messages = ctx.getMessages();

            String userContent = message;
            if (filePaths != null && !filePaths.isEmpty()) {
                // ... 文件处理逻辑完全不变 ...
                StringBuilder sb = new StringBuilder();
                sb.append("[用户上传了以下文件，请根据扩展名使用对应的 skill 处理]\n");
                for (String p : filePaths) {
                    String ext = "";
                    String name = p.substring(p.lastIndexOf('/') + 1);
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx > 0) {
                        ext = name.substring(dotIdx).toLowerCase();
                        int usIdx = name.indexOf('_');
                        if (usIdx > 0 && usIdx < dotIdx) name = name.substring(usIdx + 1);
                    }
                    String skillHint = switch (ext) {
                        case ".pdf" -> " → 调用 use_skill(\"pdf\")";
                        case ".xlsx", ".xls" -> " → 调用 use_skill(\"xlsx\")";
                        case ".docx", ".doc" -> " → 调用 use_skill(\"docx\")";
                        case ".pptx", ".ppt" -> " → 调用 use_skill(\"pptx\")";
                        case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp" -> " → 调用 use_skill(\"image-analysis\")";
                        default -> "";
                    };
                    sb.append("- ").append(name).append(skillHint).append("\n");
                    sb.append("  路径: ").append(p).append("\n");
                }
                sb.append("\n用户消息: ").append(message);
                userContent = sb.toString();
            }
            messages.add(Map.of("role", "user", "content", userContent));

            // 3. 持久化 user 消息
            MessageEntity userMsg = new MessageEntity();
            userMsg.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
            userMsg.setConversationId(cid);
            userMsg.setRole("user");
            userMsg.setContent(message);
            userMsg.setCreatedAt(msgSeq++);
            chatDao.insertMessage(userMsg);

            handle.checkCancel();

            // 4. 工具调用循环
            LlmResult result = null;
            int totalInputTokens = 0, totalOutputTokens = 0, rounds = 0;
            while (true) {
                handle.checkCancel();
                result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter, cid);
                rounds++;
                if (result.getUsage() != null) {
                    totalInputTokens += result.getUsage().getOrDefault("inputTokens", 0);
                    totalOutputTokens += result.getUsage().getOrDefault("outputTokens", 0);
                }

                handle.touch();

                if (result.hasToolCalls()) {
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                    if (result.getToolCalls() != null) {
                        assistantMsg.put("tool_calls", result.getToolCalls().stream().map(tc -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", tc.getId());
                            m.put("type", "function");
                            m.put("function", Map.of("name", tc.getName(), "arguments", gson.toJson(tc.getArguments())));
                            return m;
                        }).toList());
                    }
                    messages.add(assistantMsg);
                    persistAssistantMessage(cid, result, msgSeq++);

                    List<String> enabledKbIds = cid != null ? knowledgeDao.queryKbIdsByConversation(cid) : List.of();
                    List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName, enabledKbIds);

                    for (int i = 0; i < result.getToolCalls().size(); i++) {
                        LlmResult.ToolCall tc = result.getToolCalls().get(i);
                        String output = toolResults.get(i).get("content").toString();
                        try {
                            emitter.send(SseEmitter.event().name("message")
                                    .data(ChatEvent.builder()
                                            .type("tool_result")
                                            .toolName(tc.getName())
                                            .toolOutput(output)
                                            .build()));
                        } catch (IOException e) {
                            log.warn("推送 tool_result 事件失败: {}", e.getMessage());
                        }
                    }

                    messages.addAll(toolResults);

                    for (int i = 0; i < result.getToolCalls().size(); i++) {
                        LlmResult.ToolCall tc = result.getToolCalls().get(i);
                        MessageEntity toolMsg = new MessageEntity();
                        toolMsg.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
                        toolMsg.setConversationId(cid);
                        toolMsg.setRole("tool");
                        toolMsg.setContent(toolResults.get(i).get("content").toString());
                        toolMsg.setToolCallId(tc.getId());
                        toolMsg.setCreatedAt(msgSeq++);
                        chatDao.insertMessage(toolMsg);
                    }

                    handle.checkCancel();
                } else {
                    if (isNotBlank(result.getContent()) || result.hasToolCalls()) {
                        persistAssistantMessage(cid, result, msgSeq++);
                    }
                    if (rounds > 1 || totalInputTokens + totalOutputTokens > 0) {
                        log.info("对话完成 — 共 {} 轮, 输入: {} tokens, 输出: {} tokens, 合计: {} tokens",
                                rounds, totalInputTokens, totalOutputTokens, totalInputTokens + totalOutputTokens);
                    }
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("done").build()));
                    completed = true;
                    return;
                }
            }
        } catch (ConversationSessionManager.CancelSessionException e) {
            log.debug("会话已被取消: {}", e.getMessage());
            // 取消在途 LLM 调用
            llmClient.cancelCall(cid);
        } catch (Exception e) {
            boolean disconnected = e instanceof IOException
                    && e.getMessage() != null && e.getMessage().contains("CANCEL");
            if (disconnected) {
                log.debug("SSE 客户端已断开，终止对话");
            } else {
                log.error("Chat error", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("error").content(e.getMessage()).build()));
                } catch (Exception ignored) {}
            }
        } finally {
            if (!completed) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
            sessionManager.remove(cid);
        }
    });

    return emitter;
}
```

- [ ] **Step 3: 修改 handleSlashCommand — /clear 改为合作式取消**

`/clear` case 修改为设置 cancel 标志 + 抛异常，让 finally 统一清理：

```java
case "clear" -> {
    chatDao.deleteMessagesByConvId(cid);
    chatDao.deleteConversation(cid, userName);
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("clear").build()));
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("done").build()));
    // 原来直接 emitter.complete() → 现在改由 finally 统一处理
    // 原来 return null → 现在抛 CancelSessionException 让 finally 收尾
    throw new ConversationSessionManager.CancelSessionException(cid);
}
```

`/help` 和 `/context` ——在 `emitter.complete()` 前设置 `completed = true`，避免 finally 中重复 complete。

注意：`handleSlashCommand` 方法签名需要新增 `SessionHandle handle` 参数：

```java
private String handleSlashCommand(String message, String userName, String cid,
                                   SseEmitter emitter, long now,
                                   ConversationSessionManager.SessionHandle handle) throws IOException {
```

但 `/help` 和 `/context` 不需要 handle——它们直接 complete 和 return null，不涉及取消。

- [ ] **Step 4: 修改 cancel() 方法 — cancel(userName) 迭代所有会话**

LlmClient.activeCalls 的 key 已改为 conversationId，`cancel(userName)` 不能再直接调 `llmClient.cancelCall(userName)`。改为遍历 SessionManager 中该用户的所有活跃会话，逐个 cancel：

```java
@Override
public void cancel(String userName) {
    // 取消该用户所有活跃会话（合作式取消，不强制 kill）
    // 注意：activeCalls 的 key 已是 conversationId，不能直接传 userName
    // 通过 sessionManager 遍历该用户的所有 Handle
    // 此方法由 ChatCtrl.cancel() 调用（无 conversationId 时走此路径）
}

// 新增：按 conversationId 取消
public void cancelByConversationId(String conversationId) {
    ConversationSessionManager.SessionHandle handle = sessionManager.get(conversationId);
    if (handle != null) {
        handle.cancel();
    }
}
```

> **注：** `cancel(userName)` 的遍历实现可利用 `sessionManager` 在 Task 2 中暂不暴露 `listByUser()` 方法，在 Task 6 实现时一并补充。实际就是调用 `cancelByConversationId` 遍历该用户的 session。

`ChatService` 接口新增 `void cancelByConversationId(String conversationId);`。

- [ ] **Step 5: 实施注意事项**

- `dispatchTools()` 内部每个 MCP 工具调用可能耗时最长 60 秒——`checkCancel()` 放在工具循环前后，不在 `dispatchTools()` 内部。取消延时最多一个工具调用的时长，可接受。
- `final boolean completed` 标志在 `/help`、`/context` 正常完成路径中设为 true，在工具调用循环正常退出时也设为 true。其他路径（CancelSessionException、一般 Exception）completed 保持 false，由 finally 中 `if (!completed)` 守卫完成 emitter。
- `handleSlashCommand` 方法签名增加 `SessionHandle handle` 参数，但仅在 `/clear` 中使用。

- [ ] **Step 6: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: ChatServiceImpl改为自清理try-finally，合作式取消，cid预生成"
```

---

### Task 5: ConversationServiceImpl.delete() — 集成会话取消

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ConversationServiceImpl.java`

- [ ] **Step 1: 注入 ConversationSessionManager 和 ChatServiceImpl，delete 前设 cancel**

```java
// 新增注入
@Resource
private ConversationSessionManager sessionManager;

// 修改 delete() 方法
@Override
@Transactional
public void delete(String id, String userName) {
    ConversationEntity conv = chatDao.queryConversationById(id);
    if (conv == null || !conv.getUserName().equals(userName)) {
        throw new RuntimeException("无权限或记录不存在");
    }
    // 通知异步任务取消（如果该会话正好在活跃对话中）
    ConversationSessionManager.SessionHandle handle = sessionManager.get(id);
    if (handle != null) {
        handle.cancel();
    }
    chatDao.deleteMessagesByConvId(id);
    chatDao.deleteConversation(id, userName);
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ConversationServiceImpl.java
git commit -m "feat: 删除会话时先通知异步任务取消再删DB"
```

---

### Task 6: ChatCtrl — SSE 回调委派，cancel 接口增加 conversationId

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java`

- [ ] **Step 1: cancel 接口支持 conversationId**

`ChatService` 接口新增方法：

```java
void cancelByConversationId(String conversationId);
```

`ChatCtrl.cancel()` 改为接受可选 `conversationId`：

```java
@Operation(summary = "取消当前对话")
@RequestMapping(path = "/cancel", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_cancel", apiDesc = "聊天接口_取消当前对话")
public ReturnMessage<?> cancel(@RequestBody(required = false) Map<String, String> body) {
    if (body != null && body.containsKey("conversationId") && !body.get("conversationId").isEmpty()) {
        chatService.cancelByConversationId(body.get("conversationId"));
    } else {
        chatService.cancel(userName());  // 无 conversationId 时取消用户所有活跃会话
    }
    return ReturnMessage.success("ok");
}
```

> 使用 `Map<String, String>` 接收请求体，避免新建 DTO 类。`cancel(userName)` 遍历 SessionManager 中该用户的所有会话逐个 cancel（Task 4 已实现）。

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

预期: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java
git commit -m "feat: ChatCtrl cancel接口支持按conversationId取消"
```

---

### Task 7: 集成验证 — 构建 + 启动测试

- [ ] **Step 1: 完整构建**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests -q
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
cp -rf src/main/resources/*.properties target/classes/
```

预期: BUILD SUCCESS

- [ ] **Step 2: 启动后端测试基础功能**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 3: curl 验证 — 正常发送消息**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"你好","mode":"default"}'
```

预期: SSE 流式返回 token + done 事件，无异常

- [ ] **Step 4: curl 验证 — 取消对话**

先获取 conversationId（从上一步的 meta 事件中），然后：

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/cancel" \
  -d '{"conversationId":"<上一步的cid>"}'
```

预期: `{"state":"OK","body":"ok"}`

- [ ] **Step 5: curl 验证 — 删除会话**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/conversations/delete" \
  -d '{"id":"<cid>"}'
```

预期: 返回成功，无异常。如果会话有活跃对话，异步任务被取消。

- [ ] **Step 6: Commit**（如有调整）

---

### Task 8: 前端构建 + 端到端测试

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static
npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 2: 浏览器打开验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

验证：
- 正常对话流程无异常
- 刷新页面不会导致旧对话崩溃
- 删除会话后列表正确更新

---

## Task Dependencies

```
Task 1 (配置) ──┐
                ├── Task 2 (SessionManager) ──┐
                │                              ├── Task 4 (ChatServiceImpl) ──┬── Task 5 (ConversationServiceImpl)
                └── Task 3 (LlmClient) ────────┘                              │
                                                                               ├── Task 6 (ChatCtrl)
                                                                               │
                                                                               └── Task 7 (验证) ── Task 8 (前端)
```
