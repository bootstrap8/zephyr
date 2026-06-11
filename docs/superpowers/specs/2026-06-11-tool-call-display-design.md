# 工具调用实时展示

## 目标

聊天页面增加模型工具调用输出内容展示，固定在推理内容下面，类似思考气泡效果，标题显示"正在执行的工具名 + 历时"，支持折叠/展开查看输入参数和返回结果。

## 现状

- 后端 `LlmClient` 在流式过程中累积 tool_calls 但不实时推送 SSE 事件，只在 `finish_reason=tool_calls` 时汇总到 `LlmResult` 返回
- 前端 `ToolCallCard.vue` 已存在，有 running/success/error 状态标签，但只在 `streaming=true` 时渲染
- `ChatEvent` 已定义 `tool_call`/`tool_result` 类型但未使用

## 方案

### 后端

**LlmClient.java**
- delta 中出现 `tool_calls` 时，解析到 `name` 后发送 SSE 事件：`type: "tool_call"`, `toolName`, `toolInput`, `status: "running"`
- 去重：用 `JsonObject` 记录已发送的 name，同一轮不重复推送

**ChatServiceImpl.java**
- `dispatchTools()` 执行后，对每个工具结果发送 SSE 事件：`type: "tool_result"`, `toolName`, `toolOutput`, `status: "success"` 或 `"error"`

### 前端

**ChatView.vue**
- 新增 `tool_call` 事件处理 → `chatStore.upsertToolCall(event.toolName, { input: event.toolInput, status: 'running' })`
- 新增 `tool_result` 事件处理 → `chatStore.upsertToolCall(event.toolName, { status: 'success'/'error', output: event.toolOutput })`

**chat.ts (store)**
- 新增 `upsertToolCall(name, patch)`：找到最后一条 assistant 消息，在 `toolCalls` 数组中匹配 name，存在则 merge patch，不存在则 push 新项

**ToolCallCard.vue**
- 移除 `v-if="streaming"`，toolCalls 始终渲染
- 增加折叠/展开交互（默认折叠，类似 ThinkingBlock）
- 增加耗时计时器（`animating` 为 true 时启动，false 时停止并保留最终耗时）
- 新增 prop `animating: boolean`，由父组件传入
- 展开时显示输入参数和返回结果（JSON 格式化）

**MessageBubble.vue**
- 移除 `v-if="streaming"`，改为直接 `v-for` 渲染 toolCalls
- 每个 `ToolCallCard` 传入 `:animating="streaming"`

## 关键行为

| 场景 | 行为 |
|------|------|
| 流式进行中 | tool_call 卡片显示"执行中"标签 + 实时耗时 + 动画点 |
| 流式结束后 | 保留卡片，显示"成功/失败"标签 + 最终耗时，可展开查看详情 |
| 刷新/切回对话 | 从历史消息中恢复 toolCalls，显示完成态，耗时不可用（历史无计时数据） |
| 多轮工具调用 | 每轮的工具调用追加到同一 assistant 消息的 toolCalls 数组 |
