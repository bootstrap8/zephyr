# SSE 空闲超时修复

## 背景

当前两个 15 分钟超时机制在对话进行中误触发：

| 机制 | 当前值 | 问题 |
|------|--------|------|
| `sse.timeoutMillis` | 900,000ms（总存活时间） | 对话超 15 分钟就杀连接，不管是否在活跃输出 |
| `sessionIdleTimeoutSeconds` | 900s | `touch()` 只在 `llmClient.chat()` 返回后调用，流式期间不更新 |

实际观察：LLM 仍在持续输出内容阶段触发了 SSE 超时 + 空闲取消。

## 方案

### 1. SSE 超时：去掉存活时间限制

`timeoutMillis` 改为 `Long.MAX_VALUE`。Java/Servlet 栈级别安全（Spring 6.x + Tomcat 10.x 均支持）。YAML 中不写入该值（移除 `timeout-millis` 配置项），仅依赖 Java 默认值。

### 2. 空闲超时：流式 + 工具执行期间持续 touch

`LlmClient.chat()` 加 `SessionHandle` 参数，每收到一个流式 chunk 调用：

- `handle.touch()` — 刷新 `lastActivityTime`
- `handle.checkCancel()` — 客户端断连时快速中断 OkHttp 流

`dispatchTools()` 返回后也需调用 `handle.touch()`，防止慢速工具执行接近空闲超时。

### 3. 配置默认值变更

| 配置项 | 旧默认 | 新默认 |
|--------|--------|--------|
| `sse.timeoutMillis` | 900,000 | `Long.MAX_VALUE` |
| `sessionIdleTimeoutSeconds` | 900 | 1800 |
| `readTimeoutSeconds` | 120 | 300 |

`readTimeoutSeconds` 提升是因为：长时间工具执行 + 思考模型慢响应场景下，OkHttp 120s 读超时可能比空闲超时更早触发。

### 4. 保留 scanExpired 兜底

不删除 `scanExpired()` 定时扫描，防止异常情况下的 session 泄漏。

## 三个场景

| 场景 | 清理路径 |
|------|----------|
| 流式输出中客户端断开 | `emitter.onError` → `handle.cancel` → 下个 chunk `checkCancel` → 中断 OkHttp + 关闭 SSE |
| 对话结束后 30 分钟无交互 | `scanExpired` → `handle.cancel`（异步任务已结束，session 上次 `chat()` 返回时已从 map remove） |
| 对话进行中（流式 + 工具调用） | 不触发任何超时，`touch()` 在每个 chunk 和每次工具执行后刷新 |

## 影响范围

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | `sse.timeoutMillis` → `Long.MAX_VALUE`，`sessionIdleTimeoutSeconds` → 1800，`readTimeoutSeconds` → 300 |
| `application.yml` | 移除 `timeout-millis`，更新 `session-idle-timeout-seconds` 和 `read-timeout-seconds` |
| `LlmClient.java` | `chat()` 加 `SessionHandle` 参数，chunk 回调中 `touch()` + `checkCancel()` |
| `ChatServiceImpl.java` | 传 `handle` 给 `llmClient.chat()`；`dispatchTools()` 返回后 `touch()` |

## ralplan 审核记录

Planner → Architect → Critic 三方共识，均 **APPROVE**。

- `SseEmitter(Long.MAX_VALUE)` 兼容性：Spring 6.x / Tomcat 10.x 栈安全，不会溢出
- `emitter.onCompletion()` 从 MUST 降级为 SHOULD（onError 已覆盖实际场景）
- MCP 连接空闲超时（15 分钟）不在本次范围，后续观察
