# LLM 对话接入 总体设计

## 概述

将 zephyr 的 `/chat/send` 从 mock 替换为真实的 LLM 调用，集成 MCP 工具、Skill、记忆，支持 SSE 流式返回和 tool calling 自主决策。

## 子模块

| # | 模块 | Spec |
|---|------|------|
| 1 | 会话/消息持久化 | [spec](./2026-06-09-zephyr-chat-persistence.md) |
| 2 | MCP 工具运行时调用 | [spec](./2026-06-09-zephyr-chat-mcp-call.md) |
| 3 | 上下文组装 + 模型调用 | [spec](./2026-06-09-zephyr-chat-context.md) |
| 5 | `/` 命令输入 | [spec](./2026-06-09-zephyr-chat-slash.md) |

## 核心流程

```
POST /chat/send {conversationId, message}
  │
  ├─ 1. 持久化 user 消息
  │
  ├─ 2. 上下文组装（ContextBuilder）
  │     ├─ 查 model_configs → baseUrl + apiKey + modelName
  │     ├─ 查 mcp_tools (enabled=1) → OpenAI tool definitions
  │     ├─ 查 skill_configs (enabled=1) → 索引注入 system prompt
  │     ├─ 查 memories → 索引注入 system prompt
  │     ├─ 读历史消息（最近 20 轮）
  │     └─ 组装: [system, ...history, user]
  │
  ├─ 3. 工具调用循环（最多 10 轮）
  │     ├─ LlmClient.chat() → POST /v1/chat/completions (OkHttp, SSE 流式)
  │     │   ├─ token 事件 → 转发 SseEmitter
  │     │   ├─ thinking 事件 → 转发 SseEmitter
  │     │   └─ done → 检查 tool_calls
  │     │
  │     ├─ 有 tool_calls → 工具分发
  │     │   ├─ "use_skill"  → 读 ~/.zephyr/skills/{name}/SKILL.md
  │     │   ├─ "use_memory" → MemoryService.detail()
  │     │   └─ 其他          → McpConnectionManager.callTool()
  │     │   → tool results 追加到 messages → 继续循环
  │     │
  │     └─ 无 tool_calls → 持久化 assistant 消息 → 结束
  │
  └─ 错误处理: ChatEvent(type:"error") → 超时/取消都有明确路径
```

## 新增后端文件（chat 模块）

```
chat/
├── ctrl/ChatCtrl.java              # 修改：注入 ChatService
├── service/ChatService.java        # 🆕 核心接口
├── service/impl/ChatServiceImpl.java # 🆕 核心实现
├── service/ContextBuilder.java     # 🆕 上下文组装
├── client/LlmClient.java           # 🆕 OkHttp 流式调用
├── dao/ChatDao.java                # 🆕 消息 CRUD
├── dao/entity/ConversationEntity.java # 🆕
├── dao/entity/MessageEntity.java   # 🆕
├── dao/mapper/common/ChatMapper.xml    # 🆕
├── dao/mapper/embedded/ChatMapper.xml  # 🆕
├── dao/mapper/mysql/ChatMapper.xml     # 🆕
├── dao/mapper/postgresql/ChatMapper.xml # 🆕
└── model/
    ├── ChatEvent.java              # 已有
    ├── ChatRequest.java            # 🆕 请求 DTO
    ├── ToolDef.java                # 🆕 OpenAI tool definition
    └── LlmResult.java              # 🆕 LLM 返回
```

## 新增/修改 MCP 文件

```
mcp/utils/
├── McpClient.java               # 修改：新增 callTool()
├── McpConnectionManager.java    # 🆕 连接管理器
└── McpConnection.java           # 🆕 连接抽象
```

## 修改前端文件

```
src/views/chat/
├── InputArea.vue      # 修改：/ 命令菜单
├── SlashMenu.vue      # 🆕
├── ModelPicker.vue    # 🆕
├── McpListPanel.vue   # 🆕
├── SkillsListPanel.vue # 🆕
├── MemoryListPanel.vue # 🆕
├── ResumePanel.vue    # 🆕
└── ContextPanel.vue   # 🆕
```

## 新增数据库表

- `conversations`：会话（id, user_name, title, created_at, updated_at）
- `messages`：消息（id, conversation_id, role, content, thinking, tool_calls_json, tool_call_id, created_at）

## 新增依赖

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>
```

## 实现顺序

1 → 2 → 3（含 4 + 6）→ 5

先做持久化（基础设施），再做 MCP 工具调用（前置能力），然后组装上下文+模型调用（核心流程），最后 `/` 命令（前端体验）。
