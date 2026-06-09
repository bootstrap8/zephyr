# 上下文组装 & 工具分发 设计规格

## 概述

每次调 LLM 前，从多个来源拼装完整的 messages 数组；LLM 返回 tool_calls 时分发到内置处理器或 MCP 连接管理器。这是对话模块的核心中枢。

## 上下文组装流程

```
ChatService.send(userName, conversationId, userMessage)
  │
  ├─ 1. 加载模型配置
  │     model_configs WHERE user_name=? AND is_default=1
  │     → baseUrl, apiKey, modelName
  │
  ├─ 2. 加载 MCP 工具 → OpenAI tool definitions
  │     mcp_tools WHERE user_name=? AND enabled=1
  │     → [{type:"function", function:{name, description, parameters}}]
  │
  ├─ 3. 加载 Skills 索引
  │     skill_configs WHERE user_name=? AND enabled=1
  │     → "## 可用技能\n- {skillName}: {description}\n..."
  │
  ├─ 4. 加载记忆索引
  │     MemoryService.list(null, userName)
  │     → "## 用户记忆\n- {name} ({type}): {description}\n..."
  │
  ├─ 5. 组装 system prompt
  │     = 角色定义 + 行为约束
  │       + Skill 索引（name + description）
  │       + 记忆索引（name + type + description）
  │       + 描述 use_skill / use_memory 两个内置工具的用法
  │
  ├─ 6. 加载历史消息
  │     messages WHERE conversation_id=? ORDER BY created_at ASC
  │     → 最近 N 轮（默认 20 轮 = 40 条消息）
  │
  └─ 7. 组装 messages 数组
       [
         {role:"system", content: systemPrompt},
         ...historyMessages,
         {role:"user", content: userMessage}
       ]
```

## System Prompt 结构

```
你是一个 AI 助手，名为 zephyr。

{角色定义和行为约束}

## 可用技能
{每个已启用 skill 一行：name + description}
（需要某个技能的详细指导时，使用 use_skill 工具加载）

## 用户记忆
{每条记忆一行：name (type): description}
（需要查看某条记忆的完整内容时，使用 use_memory 工具加载）

## 工具使用说明
- 你拥有 MCP 工具、技能、记忆三种能力
- 技能和记忆通过 use_skill / use_memory 按需加载
- 优先使用 MCP 工具获取实时数据
```

## 内置工具定义

发给 LLM 的 tool definitions = 内置工具 + MCP 工具：

```json
// 内置工具
{
  "type": "function",
  "function": {
    "name": "use_skill",
    "description": "加载指定 skill 的完整指导内容到上下文",
    "parameters": {
      "type": "object",
      "properties": {
        "skill_name": {"type": "string", "description": "技能名称"}
      },
      "required": ["skill_name"]
    }
  }
},
{
  "type": "function",
  "function": {
    "name": "use_memory",
    "description": "查看指定记忆的完整内容",
    "parameters": {
      "type": "object",
      "properties": {
        "memory_name": {"type": "string", "description": "记忆名称"}
      },
      "required": ["memory_name"]
    }
  }
}
```

## 工具分发

```
LLM 返回 tool_calls
  │
  ├─ tool_name == "use_skill"
  │   → 读 ~/.zephyr/skills/{skill_name}/SKILL.md
  │   → 返回正文（去掉 YAML frontmatter）
  │   → 追加 tool role message: {role:"tool", tool_call_id, content: skillContent}
  │
  ├─ tool_name == "use_memory"
  │   → MemoryService.detail(memory_name, userName)
  │   → 追加 tool role message: {role:"tool", tool_call_id, content: memory.content}
  │
  └─ 其他 tool_name（MCP 工具）
      → 匹配 mcp_tools WHERE tool_name=? AND user_name=?
      → McpConnectionManager.getConnection(userName, serverId)
      → McpClient.callTool(connection, toolName, arguments)
      → 追加 tool role message: {role:"tool", tool_call_id, content: result}
```

## 工具调用循环

```
最多 10 轮
  │
  ├─ 调 LLM（SSE 流式）
  │   ├─ 收到 "token" 事件 → 转发前端
  │   ├─ 收到 "thinking" 事件 → 转发前端
  │   └─ 收到 "done" 事件
  │       ├─ 有 tool_calls？→ 分发执行 → 结果追加到 messages → 继续下一轮
  │       └─ 无 tool_calls？→ 结束，持久化消息
  │
  └─ 超 10 轮未结束 → 强制终止，返回已生成内容
```

## 消息持久化时机

- user 消息：调用前立刻写入 DB
- assistant 消息：SSE 流式完成后写入（content + thinking + tool_calls_json）
- tool 消息：每轮 tool call 执行后立刻写入

## Token 预算

| 部分 | 策略 |
|------|------|
| system prompt（角色 + skill 索引 + 记忆索引） | 预估 1-3K token，全量 |
| use_skill 加载后内容 | 按需，加载后进入 messages 历史 |
| use_memory 加载后内容 | 按需，加载后进入 messages 历史 |
| 历史消息 | 最近 20 轮（40 条），超出截断 |
| MCP 工具返回 | 单次最大 8000 字符，超出截断 |

## 上下文占比接口

```
GET /chat/context-usage?conversationId={id}

返回当前会话的 token 估算分布：
{
  "systemPrompt": 1200,
  "history": 4500,
  "skillContent": 0,
  "memoryContent": 0,
  "toolDefinitions": 800,
  "total": 6500
}
```

实现方式：ChatCtrl 新增方法，注入 ChatService，后者基于 ContextBuilder 组装的各部分计算字符数 × 0.3（粗略 token 估算）。

## 新增/修改文件

```
chat/
├── ctrl/ChatCtrl.java             # 修改：注入 ChatService，替换 mock
├── service/ChatService.java       # 🆕 核心服务接口
├── service/impl/ChatServiceImpl.java # 🆕 核心实现（上下文组装 + 工具分发 + 循环）
├── service/ContextBuilder.java    # 🆕 上下文组装器（system prompt + tools）
├── model/
│   ├── ChatEvent.java             # 已有
│   └── ToolDef.java               # 🆕 OpenAI tool definition DTO
```

依赖：
- `ConfigDao`（查 model_configs）
- `McpDao`（查 mcp_tools）
- `SkillDao`（查 skill_configs）
- `SkillService`（读 SKILL.md 文件）
- `MemoryService`（读记忆全文）
- `McpConnectionManager`（MCP 工具调用）
- `ChatDao`（消息持久化，来自 #1）

---

## 模型调用 & SSE 流式

### HTTP 客户端：OkHttp

新增依赖：

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>
```

### LlmClient（chat/client/LlmClient.java）

封装 OkHttp，提供流式调用能力：

```
POST {baseUrl}/v1/chat/completions
Authorization: Bearer {apiKey}
Content-Type: application/json
Body: {
  model: modelName,
  messages: [...],
  tools: [...],
  stream: true,
  stream_options: {include_usage: true}
}
```

SSE 响应解析：

```
Response body 逐行读取
  ├─ "data: {"choices":[{"delta":{"content":"你好"}}]}"
  │   → emitter.send(ChatEvent(type:"token", content:"你好"))
  │
  ├─ "data: {"choices":[{"delta":{"reasoning_content":"..."}}]}"（DeepSeek 思考）
  │   → emitter.send(ChatEvent(type:"thinking", content:"..."))
  │
  ├─ "data: {"choices":[{"delta":{"tool_calls":[...]}}]}"
  │   → 累积 tool_calls，不立即发送（完成后再处理）
  │
  └─ "data: [DONE]"
      → emitter.send(ChatEvent(type:"done", usage))
      → emitter.complete()
```

### ChatServiceImpl 核心流程

```
public SseEmitter send(String userName, M request) {
    SseEmitter emitter = new SseEmitter(300000L);

    executor.execute(() -> {
        try {
            // 1. 上下文组装（ContextBuilder）
            Context ctx = contextBuilder.build(userName, request);

            // 2. 持久化 user 消息
            chatDao.insertMessage(userMsg);

            // 3. 工具调用循环
            List<Map> messages = ctx.messages();
            for (int round = 0; round < 10; round++) {
                // 3a. 调 LLM 流式
                LlmResult result = llmClient.chat(ctx.model(), messages, ctx.tools(), emitter);

                if (result.hasToolCalls()) {
                    // 3b. 工具分发
                    List<Map> toolResults = dispatchTools(result.toolCalls(), userName);
                    messages.add(result.assistantMessage());
                    messages.addAll(toolResults);
                    // 持久化 assistant + tool 消息
                } else {
                    // 3c. 正常结束
                    // 持久化 assistant 消息
                    emitter.complete();
                    return;
                }
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    emitter.onTimeout(emitter::complete);
    return emitter;
}
```

### 取消对话

```
POST /chat/cancel
  → chatService.cancel()
  → OkHttp call.cancel()
  → emitter.complete()
```

### 错误处理

| 场景 | 处理 |
|------|------|
| 连接超时 | connect 30s，read 120s |
| API 4xx/5xx | 读取 error body → ChatEvent(type:"error") |
| 流中断 | IOException → ChatEvent(type:"error") |
| 用户取消 | call.cancel() → emitter.complete() |

### Prompt Caching

Anthropic 风格：system prompt 末尾加 `cache_control` breakpoint：

```json
{
  "role": "system",
  "content": [
    {"type": "text", "text": "..."},
    {"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}
  ]
}
```

非 Anthropic API 自动忽略，不影响调用。后续消息带上前面的 `cache_control` 引用可降低 token 成本。

### 新增文件（补充）

```
chat/
├── ctrl/ChatCtrl.java              # 修改：注入 ChatService，替换 mock
├── service/ChatService.java        # 🆕 核心服务接口
├── service/impl/ChatServiceImpl.java # 🆕 核心实现
├── service/ContextBuilder.java     # 🆕 上下文组装器
├── client/LlmClient.java           # 🆕 OkHttp 封装
├── model/
│   ├── ChatEvent.java              # 已有
│   ├── ToolDef.java                # 🆕 OpenAI tool definition DTO
│   └── LlmResult.java              # 🆕 LLM 返回结果（content + thinking + toolCalls）
```
