# SSE 空闲超时修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 LLM 流式输出期间 SSE 超时 + 会话空闲误杀的问题，两套 15 分钟超时均改为按实际空闲判定。

**Architecture:** 配置默认值变更 + `LlmClient.chat()` 签名加 `SessionHandle` 参数，流式 chunk 和工具执行后持续 `touch()` 刷新活跃时间。`scanExpired` 保留兜底。

**Tech Stack:** Java 17, Spring Boot 3.5.4, OkHttp 4.x

---

### Task 1: 配置默认值变更

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java:88-91`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java:125-129`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java:73-74`

- [ ] **Step 1: 改 `Sse.timeoutMillis` 默认值为 `Long.MAX_VALUE`**

编辑 `ZephyrConfigProperties.java`:

```java
@Data
public static class Sse {
    /** SSE 连接超时（毫秒），默认无限制（会话空闲超时驱动生命周期） */
    private long timeoutMillis = Long.MAX_VALUE;
}
```

- [ ] **Step 2: 改 `sessionIdleTimeoutSeconds` 默认值为 1800**

```java
/** 会话空闲超时秒数，默认 1800（30 分钟），超时后自动标记取消 */
private int sessionIdleTimeoutSeconds = 1800;
```

- [ ] **Step 3: 改 `readTimeoutSeconds` 默认值为 300**

```java
@Data
public static class Client {
    /** 建立 TCP 连接超时（秒） */
    private int connectTimeoutSeconds = 30;
    /** 等待响应数据超时（秒），默认 300（5 分钟），覆盖长时间工具执行后的慢响应 */
    private int readTimeoutSeconds = 300;
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn clean compile -q
```

---

### Task 2: application.yml 配置同步

**Files:**
- Modify: `src/main/resources/application.yml:245-246`
- Modify: `src/main/resources/application.yml:255`
- Modify: `src/main/resources/application.yml:260`

- [ ] **Step 1: 移除 `timeout-millis` 配置项**

把:
```yaml
    sse:
      timeout-millis: 900000  # SSE 连接超时（毫秒），15 分钟，对齐会话超时
```

改为（移除 `timeout-millis` 行，只保留 `sse` 键或直接移除整个 `sse` 块如果它只有这一个 key）:

```yaml
    sse:
      # SSE 连接存活时间不再设限（Java 默认 Long.MAX_VALUE），由会话空闲超时驱动生命周期
```

- [ ] **Step 2: 更新 `session-idle-timeout-seconds` 为 1800**

把:
```yaml
    session-idle-timeout-seconds: 900  # 会话空闲超时（秒），15 分钟无活动自动取消
```

改为:
```yaml
    session-idle-timeout-seconds: 1800  # 会话空闲超时（秒），30 分钟无活动自动取消
```

- [ ] **Step 3: 更新 `read-timeout-seconds` 为 300**

把:
```yaml
      read-timeout-seconds: 120  # LLM API 读取超时
```

改为:
```yaml
      read-timeout-seconds: 300  # LLM API 读取超时，5 分钟，覆盖长时间工具执行后的慢响应
```

- [ ] **Step 4: 编译验证**

```bash
mvn clean compile -q
```

---

### Task 3: LlmClient.chat() 加 SessionHandle 参数 + 流式内 touch/checkCancel

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java:3`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java:56-57`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java:130-131`

- [ ] **Step 1: 添加 import**

在 `LlmClient.java` 第 3 行后添加:

```java
import com.github.hbq969.ai.zephyr.chat.service.ConversationSessionManager;
```

- [ ] **Step 2: 修改 `chat()` 方法签名**

把第 56-57 行:
```java
public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                      List<ToolDef> tools, SseEmitter emitter, String conversationId) throws IOException {
```

改为:
```java
public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                      List<ToolDef> tools, SseEmitter emitter, String conversationId,
                      ConversationSessionManager.SessionHandle handle) throws IOException {
```

- [ ] **Step 3: 流式循环内 `emitter.send()` 前加 `handle.touch()` + `handle.checkCancel()`**

在 `while ((line = reader.readLine()) != null)` 循环体内，`line.startsWith("data: ")` 的 `[DONE]` 检查之后、`JsonObject event = gson.fromJson(...)` 之前，添加:

```java
while ((line = reader.readLine()) != null) {
    if (line.startsWith("data: ")) {
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) break;

        handle.touch();
        handle.checkCancel();

        try {
            JsonObject event = gson.fromJson(data, JsonObject.class);
            // ... 后续不变
```

- [ ] **Step 4: `getTimeoutSeconds()` 默认值从 120 改为 300**

把第 271、278 行的:
```java
private int getTimeoutSeconds(Map<String, Object> params) {
    if (params == null) return 120;
    Object v = params.get("request_timeout");
    if (v instanceof Number) {
        int t = ((Number) v).intValue();
        return t > 0 ? t : 120;
    }
    return 120;
}
```

改为:
```java
private int getTimeoutSeconds(Map<String, Object> params) {
    if (params == null) return 300;
    Object v = params.get("request_timeout");
    if (v instanceof Number) {
        int t = ((Number) v).intValue();
        return t > 0 ? t : 300;
    }
    return 300;
}
```

- [ ] **Step 5: 修复 OkHttpClient 超时比较硬编码 `120`**

把第 90 行:
```java
        OkHttpClient client = (timeout != 120)
```

改为:
```java
        OkHttpClient client = (timeout != cfg.getLlm().getClient().getReadTimeoutSeconds())
```

- [ ] **Step 6: 编译验证**

```bash
mvn clean compile -q
```

---

### Task 4: ChatServiceImpl 传 handle + dispatchTools 后 touch

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java:182`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java:208`

- [ ] **Step 1: `llmClient.chat()` 调用处传入 `handle`**

把第 182 行:
```java
result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter, cid);
```

改为:
```java
result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter, cid, handle);
```

- [ ] **Step 2: `dispatchTools()` 返回后调用 `handle.touch()`**

在第 208 行 `dispatchTools(...)` 返回后、`for` 循环前添加 `handle.touch()`:

```java
List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName, enabledKbIds, cid);
handle.touch();

for (int i = 0; i < result.getToolCalls().size(); i++) {
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```

---

### Task 5: 端到端验证

- [ ] **Step 1: 确认编译通过**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 2: 确认配置默认值正确**

```bash
grep -n 'timeoutMillis\|sessionIdleTimeoutSeconds\|readTimeoutSeconds' src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java
```
Expected:
```
90:    private long timeoutMillis = Long.MAX_VALUE;
74:    private int sessionIdleTimeoutSeconds = 1800;
128:   private int readTimeoutSeconds = 300;
```

- [ ] **Step 3: 确认 application.yml 无 Long.MAX_VALUE 字面量**

```bash
grep '9223372036854775807\|Long.MAX_VALUE' src/main/resources/application.yml
```
Expected: no matches

- [ ] **Step 4: 启动后端验证无启动异常**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```
Expected: 启动无异常，日志无 "SSE 超时" 相关错误

- [ ] **Step 5: curl 验证对话功能**

```bash
curl -N -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"你好","mode":"default"}'
```
Expected: SSE 流式返回正常，连接不会在 15 分钟时断开

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java \
        src/main/resources/application.yml \
        src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java \
        docs/superpowers/specs/2026-06-24-sse-idle-timeout-fix-design.md \
        docs/superpowers/plans/2026-06-24-sse-idle-timeout-fix.md
git commit -m "$(cat <<'EOF'
fix: SSE 空闲超时误杀活跃对话

- SSE timeout 改为 Long.MAX_VALUE，不再按存活时间杀连接
- 空闲超时从 15 分钟改为 30 分钟，可配置
- LlmClient 流式 chunk 和工具执行后持续 touch 刷新活跃时间
- OkHttp 读超时从 120s 提升到 300s，覆盖长时间工具执行场景
- 保留 scanExpired 兜底防止 session 泄漏

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```
