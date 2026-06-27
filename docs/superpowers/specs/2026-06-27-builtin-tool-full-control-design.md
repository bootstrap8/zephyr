# 内置工具全面角色管控

**日期：** 2026-06-27
**状态：** Architect + Critic 审核完成（v2 — 待最终批准）

## 背景

当前只有 `execute_shell`、`list_processes`、`kill_process` 三个 shell 族工具有角色管控。其余工具缺少角色层面的准入控制，非 admin 用户可直接调用。

## 目标

将角色管控覆盖到除纯读类工具（`read`、`grep`、`glob` 等）之外的全部工具：

- 文件写入类：`write_file`、`edit_file`
- Zephyr 内部工具：`use_skill`、`use_memory`、`search_knowledge`
- MCP 外部工具：全局开关 `mcp_all`

## 设计

### 数据模型

`zephyr_builtin_tool_controls` 表结构不变，扩充种子数据。

**新增行（`require_admin=1` 表示非 admin 不可用）：**

| tool_name | description（zh-CN） | require_admin |
|-----------|---------------------|:---:|
| `write_file` | 写入/创建文件，支持覆盖和追加模式 | 1 |
| `edit_file` | 精确字符串替换编辑文件 | 1 |
| `use_skill` | 调用自定义技能模块，扩展 Agent 能力 | 0 |
| `use_memory` | 读写持久化记忆，跨会话保留上下文 | 0 |
| `search_knowledge` | 在知识库中语义检索相关文档片段 | 0 |
| `mcp_all` | MCP 外部工具全局开关（控制所有 MCP 工具的可用性） | 1 |

三语 SQL 文件的 description 各自本地化。

### BuiltinToolServiceImpl 改造

#### MCP 工具识别（注册式，非前缀约定）

MCP 工具名是原始名称（如 `codegraph_search`、`browser_navigate`），没有统一前缀。使用注册式识别：注入 `McpDao`，从 `zephyr_mcp_tools` 表加载工具名到 `Set<String> mcpToolNames`。

```
refreshCache():
  requireAdminCache = load from zephyr_builtin_tool_controls
  mcpToolNames    = load from zephyr_mcp_tools (SELECT DISTINCT tool_name)

requiresAdmin(userName, toolName):
  if admin → false (豁免)

  // MCP 工具 ? 查 mcp_all : 查具体工具名
  lookupKey = mcpToolNames.contains(toolName) ? "mcp_all" : toolName
  v = requireAdminCache.get(lookupKey)

  blocked = v != null && v
  if blocked → log + return true
  return false
```

- 不在 cache 也不在 mcpToolNames 的工具（如 `read`）→ `get()` 返回 null → 不拦截
- 新增 MCP 工具自动被识别

#### 已知限制：MCP 工具异步发现窗口

`refreshCache()` 由 `ScriptInitialDoneEvent` 触发，此时 MCP 服务器连接事件可能尚未完成，`mcpToolNames` 启动瞬间可能为空。在此期间 MCP 工具不受 `mcp_all` 管控。非安全漏洞（默认放行），后续可通过监听 MCP 连接完成事件追加刷新。

#### 新增依赖

`BuiltinToolServiceImpl` 注入 `McpDao`。McpDao 新增查询 `queryAllDistinctToolNames()`，对应 SQL 加在 `common/McpMapper.xml`（仅此一个文件，方言 XML 只放 DDL）。

### SecurityEvaluator 改造

角色检查从各 case 上提到 `evaluate()` 入口，**并确保审计日志被调用**：

```
evaluate(toolName, arguments, userName, mode, boundary):
  if !isEnabled() → ALLOW

  // 1. 全局角色检查
  if requiresAdmin(userName, toolName):
    r = BLOCK("ROLE_CHECK", "无权限（非 admin 用户）")
    auditLogger.log("SECURITY_CHECK", toolName, "BLOCK", "ROLE_CHECK: ...", userName)
    return r   // 提前返回并写入审计日志

  // 2. 具体安全策略
  result = switch toolName:
    execute_shell → evaluateShell(...)
    list_processes, kill_process → ALLOW
    write_file, edit_file → evaluateFileWrite(...)
    default → ALLOW

  if result.decision != ALLOW:
    auditLogger.log(...)
  return result
```

**关键：** ROLE_CHECK 分支中显式调用 `auditLogger.log()`，避免早期返回绕过底部的统一审计日志。

从 execute_shell / list_processes / kill_process 三个 case 中移除内联 `requiresAdmin` 调用。

### 种子数据幂等性修复

当前 `WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls)` 是表级检查，表有数据后新增行永不生效。改为逐行检查，原有 3 行也一并改：

```sql
INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'write_file', '<本地化描述>', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'write_file');
-- 以此类推，每行一条独立 INSERT ...
```

### 前端

无需改动。`ToolControlSettings.vue` 从 `/builtin-tool/list` 拉取全表，新条目自动出现。

## 涉及文件

| 文件 | 改动 |
|------|------|
| `BuiltinToolServiceImpl.java` | 注入 `McpDao`，`refreshCache` 加载 MCP 工具名集合，`requiresAdmin` 用集合判断 |
| `McpDao.java` | 新增 `queryAllDistinctToolNames()` |
| `common/McpMapper.xml` | 新增 `queryAllDistinctToolNames` SQL（仅此一个 Mapper 文件） |
| `SecurityEvaluator.java` | 角色检查上提 + ROLE_CHECK 分支显式写审计日志 |
| `zephyr-zh-CN.sql` | 原有 3 行改为逐行幂等 + 新增 6 行种子数据（中文描述） |
| `zephyr-en-US.sql` | 同上（英文描述） |
| `zephyr-ja-JP.sql` | 同上（日文描述） |
