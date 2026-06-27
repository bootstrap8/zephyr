# 工具执行结果增加"拒绝"状态

## 问题

内置工具（如 `execute_shell`）被安全评估器拒绝时（ROLE_CHECK、HARD_BLOCK、用户拒绝 SOFT_BLOCK、确认超时），前端 `ToolCallCard` 显示为"成功"——因为后端 SSE 事件未携带状态信息，前端仅通过 output 文本是否以"工具执行错误"开头来判断成功/失败。

## 目标

工具执行结果增加第三种终态：**拒绝（rejected）**，覆盖 HARD_BLOCK、用户拒绝、确认超时三种场景，统一视觉呈现。

## 设计

### 状态模型

```
running → success   (正常执行完成)
running → error     (执行时异常)
running → rejected  (安全规则拒绝 / 用户拒绝 / 超时)
```

### 后端改动

#### 1. ChatEvent 加 toolStatus 字段

`chat/model/ChatEvent.java`：

```java
private String toolStatus;  // "success" | "error" | "rejected"，用于 tool_result 事件
```

#### 2. dispatchTools 标记状态

`chat/service/impl/ChatServiceImpl.java` — `dispatchTools()`：

- BLOCK → SSE `toolStatus: "rejected"`
- 用户拒绝 → SSE `toolStatus: "rejected"`
- 确认超时 → SSE `toolStatus: "rejected"`
- 执行异常（catch Exception）→ SSE `toolStatus: "error"`
- 正常执行 → SSE `toolStatus: "success"`

#### 3. persistAssistantMessage 不再硬编码

`chat/service/impl/ChatServiceImpl.java` — `send()` 方法中：

- `persistAssistantMessage` 先按 `"running"` 写入
- `dispatchTools` 返回后，UPDATE 该消息的 `toolCallsJson`，将实际 status 写入

需要新增一个 DAO 方法 `updateMessageToolCallsJson(messageId, json)`。

### 前端改动

#### 1. 类型扩展

`types/chat.ts` — `ToolCall.status`：

```typescript
status: 'running' | 'success' | 'error' | 'rejected'
```

#### 2. SSE 事件处理

`views/chat/ChatView.vue`：

- `tool_result` 处理中，优先用 `event.toolStatus`，fallback 到文本前缀判断（兼容旧事件）
- `cancelRunningToolCalls()` 中状态设为 `'error'`，不涉及 rejected

#### 3. ToolCallCard 视觉

`views/chat/ToolCallCard.vue`：

| 状态 | 图标 | 颜色变量 | 中文 |
|------|------|----------|------|
| rejected | `lucide:shield-alert` | `--el-color-warning` | 拒绝 |

```css
.tool-status.rejected { background: rgba(230,162,60,0.12); color: var(--el-color-warning); }
```

暗黑模式同样用 `--el-color-warning`，无需额外适配。

#### 4. i18n

`i18n/locale.ts` — 三语各加一行：

| 中文 | English | 日本語 |
|------|---------|--------|
| 拒绝 | Rejected | 拒否 |

## 影响范围

| 文件 | 改动 |
|------|------|
| `chat/model/ChatEvent.java` | 加 `toolStatus` 字段 |
| `chat/service/impl/ChatServiceImpl.java` | dispatchTools 带状态、回写 toolCallsJson |
| `chat/dao/ChatDao.java` | 新增 `updateMessageToolCallsJson` |
| `chat/dao/mapper/common/ChatMapper.xml` | 新增 UPDATE SQL |
| `types/chat.ts` | status 类型加 `'rejected'` |
| `views/chat/ChatView.vue` | SSE tool_result 处理用 toolStatus |
| `views/chat/ToolCallCard.vue` | 新增加 rejected UI |
| `i18n/locale.ts` | 三语加 toolCard_rejected |
