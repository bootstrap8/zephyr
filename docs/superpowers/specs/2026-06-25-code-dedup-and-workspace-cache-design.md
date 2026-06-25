# 代码去重与 workspace 缓存优化

## 背景

安全规则外部化（`2026-06-25-security-patterns-externalize-design`）实现过程中识别了三项遗留问题，本次集中处理。

## 问题

### 1. parseCommandList 代码重复

`SecurityEvaluator.parseCommandList` 和 `ChatServiceImpl.initShellWhitelist` 中逗号分隔字符串 → `Set<String>` 的解析逻辑完全一致，重复了 ~8 行。

### 2. executeShell 重复 DB 查询

`executeShell` 每次执行都做两次 DB 查询获取 workspace 路径（第 877-885 行），而 workspace 在同一会话的消息处理周期内不会变化。

### 3. 会话级 workspace 缓存

workspace 路径无人缓存，每次 shell 执行时重复查询。

## 设计

### Part 1: 消除 parseCommandList 重复

`SecurityEvaluator.parseCommandList` 由 `private static` 改为 `public static`。`ChatServiceImpl.initShellWhitelist` 中内联解析逻辑替换为直接调用 `SecurityEvaluator.parseCommandList(raw)`。

不提取新工具类——`SecurityEvaluator` 作为安全相关静态工具方法的宿主是合理的。

### Part 2: 会话级 workspace 缓存

在 `SessionHandle` 中添加 `workspacePath` 字段，构造时传入。`send()` 中解析 workspace 路径并传给 `register()`，`executeShell` 直接从 handle 取。

**缓存生命周期**：`send()` 是每次消息的入口点，每次创建新 `SessionHandle`。workspace 切换时前端传新 `workspaceId`，下次消息自然刷新——无需主动失效机制。

```
每次消息 → send(workspaceId) → register(cid, userName, workspacePath)
  → executeShell → handle.getWorkspacePath()
下一次消息 → 新 handle, 新路径
```

## 改动清单

### SecurityEvaluator.java

| 行 | 改动 |
|----|------|
| 164 | `parseCommandList` `private static` → `public static` |

### ChatServiceImpl.java

| 行 | 改动 |
|----|------|
| 832-843 | `initShellWhitelist` 内联解析 → 调用 `SecurityEvaluator.parseCommandList` |
| 93 | `register(cid, userName)` → `register(cid, userName, workspacePath)`，workspacePath 在上方解析 |
| 877-885 | **删除** conversation + workspace DB 查询 |
| ~890 | `handle.getWorkspacePath()` 替代原 `workspacePath` 变量 |

### ConversationSessionManager.java

| 行 | 改动 |
|----|------|
| 32-33 | `register` 签名加 `String workspacePath` 参数 |
| 94-101 | `SessionHandle` 构造加 `workspacePath`，新增 getter |

### workspace 路径解析逻辑（ChatServiceImpl.send() 中新增）

```
workspaceId 非空 → workspaceDao.queryById(workspaceId) → getPath()
workspaceId 为空 → System.getProperty("user.home")
```

## 验证

- `mvn clean compile -q` 通过
- 现有功能不受影响

## 不在本次范围

- `executeShell` 中其他性能优化
- 更粗粒度的跨消息 workspace 缓存（场景不存在，无需缓存失效）
