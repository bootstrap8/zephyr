# execute_shell 内置工具设计（v2）

> **目标：** 让 LLM 能直接执行 shell 命令，zephyr 自行 fork 进程并追踪，会话结束时自动清理前台进程；后台进程解耦到用户级别管理。
> **动机：** 当前 shell 执行走 MCP 协议，进程在 MCP Server 侧 fork，zephyr 拿不到 Process 句柄，无法在会话结束时清理。

---

## 工具定义

`execute_shell` 作为 zephyr 第 4 个内置工具（与 `use_skill`、`use_memory`、`search_knowledge` 同级）。

```json
{
  "name": "execute_shell",
  "description": "在工作空间目录执行 shell 命令。前台命令（默认）阻塞等待结果返回；后台命令（background=true）立即返回，进程在用户级别管理，跨会话存活。",
  "parameters": {
    "type": "object",
    "properties": {
      "command":    { "type": "string", "description": "要执行的完整命令字符串" },
      "background": { "type": "boolean", "description": "是否后台运行，默认 false" }
    },
    "required": ["command"]
  }
}
```

LLM 还可使用两个管理工具：

```json
{
  "name": "list_processes",
  "description": "列出当前用户的所有后台进程"
}
```

```json
{
  "name": "kill_process",
  "description": "终止指定后台进程",
  "parameters": {
    "type": "object",
    "properties": {
      "pid": { "type": "integer", "description": "进程 PID" }
    },
    "required": ["pid"]
  }
}
```

## 执行语义

| background | 行为 | 生命周期归属 |
|-----------|------|-------------|
| `false` | 同步执行，阻塞等待，返回 stdout + stderr + exitCode | **会话级**：会话取消/超时/删除时 kill |
| `true` | 异步执行，立即返回 `PID: {pid}, 日志: {workspace}/.zephyr-logs/{pid}.log` | **用户级**：跨会话存活，用户注销或被管理员清理时才 kill |

## 安全性

### 命令白名单（程序化硬约束）

`ZephyrConfigProperties` 新增：

```java
@Data
public static class Shell {
    /** Shell 执行模式: disabled | whitelist | allowAll */
    private String mode = "whitelist";
    /** whitelist 模式下允许的命令（仅命令名，不含参数），默认常用 dev 工具 */
    private List<String> allowedCommands = List.of(
        "python3", "python", "node", "npm", "npx", "git", "go", "cargo",
        "java", "mvn", "gradle", "make", "cmake", "ls", "cat", "head",
        "tail", "wc", "find", "grep", "awk", "sed", "echo", "date", "env",
        "which", "mkdir", "touch", "cp", "mv", "rm", "chmod"
    );
    /** 每个用户最大后台进程数 */
    private int maxBackgroundProcesses = 5;
    /** 后台进程最大运行时间（秒），超时自动 kill */
    private int maxBackgroundLifetimeSeconds = 3600;
    /** 同步执行最大等待时间（秒） */
    private int commandTimeoutSeconds = 120;
}
```

校验逻辑：`whitelist` 模式下提取命令第一个空格前的 token，精确匹配白名单；`disabled` 完全不暴露工具；`allowAll` 不校验。

说明：白名单是**程序化硬约束**（在 `executeShell()` 入口处校验），不是 prompt 约束。即使 LLM 被注入，也无法执行白名单外的命令。

### 工作目录

固定为对话绑定的 workspace 路径。LLM 已有 `fileSystemSecurityPrompt` 的文件边界意识，shell 命令在此路径下执行。

### 文件系统安全

沿用 `fileSystemSecurityPrompt` 三级模式（default / acceptEdits / bypass），约束文件访问边界。shell 安全与文件系统安全是两个独立维度——前者管"能执行什么命令"，后者管"能访问什么文件"。

## 进程追踪（解决竞争条件）

### 槽位机制（审查 A 修复）

```java
// ConversationSessionManager.SessionHandle 中
private final List<ProcessSlot> trackedProcesses = new CopyOnWriteArrayList<>();

// 先分配槽位再启动，消除 fork→register 之间的窗口期
ProcessSlot slot = handle.reserveProcessSlot(command);
try {
    Process p = new ProcessBuilder(...).start();
    slot.bind(p);  // 填充 ProcessHandle.pid()
    // 即使 bind 前 cancel 发生，槽位已在列表中，killTrackedProcesses 能找到它
} catch (Exception e) {
    slot.markFailed();  // 标记为无效，killTrackedProcesses 跳过
    throw e;
}
```

ProcessSlot 有状态：`RESERVED → BOUND | FAILED`。`killTrackedProcesses()` 跳过 FAILED 状态，对 BOUND 状态用 `ProcessHandle.of(pid).descendants() + destroyForcibly()` 杀进程树。

### 后台进程管理（审查 C 修复）

新增 `BackgroundProcessManager`，与会话生命周期解耦：

```java
@Component
public class BackgroundProcessManager {
    // user → tracked processes
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, TrackedProcess>> userProcesses;

    long register(String userName, Process p, String command, String workspaceId);
    List<TrackedProcess> list(String userName);
    boolean kill(String userName, long pid);
    void killAll(String userName);      // 用户注销时
    void enforceLimits(String userName); // 定时扫描：超时 kill + 超量 reject
    void shutdown();                    // 应用关闭时 kill 所有
}
```

**规则：**
- `background=false` → 进程挂在 `SessionHandle.trackedProcesses`，会话级清理
- `background=true` → 进程挂在 `BackgroundProcessManager`，不随会话清理
- `BackgroundProcessManager` 强制执行：每个用户最多 N 个后台进程、每个最多 M 分钟
- 用户注销或 `@PreDestroy` 时 kill 所有后台进程

## 进程生命周期

```
前台进程 (background=false):
  LLM 调用 → execute_shell(background=false)
    → sessionHandle.reserveProcessSlot()
    → ProcessBuilder.start()
    → slot.bind(p)
    → p.waitFor(timeout)
    → slot.remove() (进程已结束)
    → 返回 stdout + stderr + exitCode

  会话取消/超时/删除:
    → handle.cancel() → finally → sessionManager.remove(cid)
    → → SessionHandle.killTrackedProcesses()
    → → → 对每个 BOUND slot: ProcessHandle.of(pid).descendants() + destroyForcibly()

后台进程 (background=true):
  LLM 调用 → execute_shell(background=true)
    → BackgroundProcessManager.enforceLimits(userName)  // 先检查配额
    → ProcessBuilder.start()
    → bgpm.register(userName, p, command, workspaceId)
    → 返回 "PID: {pid}, 日志: {workspace}/.zephyr-logs/{pid}.log"

  用户通过 LLM 查询/管理:
    → list_processes → bgpm.list(userName)
    → kill_process(pid) → bgpm.kill(userName, pid)

  后台清理:
    → @Scheduled enforceLimits() → 超时进程 kill
    → 用户注销 → bgpm.killAll(userName)
    → @PreDestroy → bgpm.shutdown()
```

## 涉及文件

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties` | 新增 `Shell` 内部类：mode / allowedCommands / maxBackgroundProcesses / maxBackgroundLifetimeSeconds / commandTimeoutSeconds |
| `application.yml` | 新增 `zephyr.shell.*` 配置节 |
| `ConversationSessionManager.SessionHandle` | 新增 `ProcessSlot` 内部类、`reserveProcessSlot()`、`killTrackedProcesses()` |
| `BackgroundProcessManager` | **新建** — 用户级后台进程管理 |
| `ContextBuilder` | 加 `buildExecuteShellTool()`、`buildListProcessesTool()`、`buildKillProcessTool()`，注册为内置工具 |
| `ChatServiceImpl` | `dispatchTools()` switch 加 `execute_shell`/`list_processes`/`kill_process` 分支；`executeShell()` 实现 |
| `ChatServiceImpl.send()` | finally 中 `sessionManager.remove(cid)` 前调用 `handle.killTrackedProcesses()` |

## 资源限制

| 限制项 | 默认值 | 说明 |
|--------|--------|------|
| 命令白名单 | 见上方列表 | whitelist 模式下硬校验 |
| 用户最大后台进程数 | 5 | 超出拒绝，提示先 kill 旧进程 |
| 后台进程最大存活时间 | 3600s | 超时自动 kill |
| 同步命令最大等待时间 | 120s | 超时 destroyForcibly + 返回 "命令超时" |
| 日志文件 | {workspace}/.zephyr-logs/{pid}.log | 进程 kill 时删除；下次启动时清理残留日志 |

## 待办事项（后续版本）

- [ ] **完整分层安全**：shell.mode (disabled | prompt | sandbox)，与 filesystem.mode 独立；sandbox 模式增加网络隔离（`denyNet`）、禁止 SUID 二进制、禁止写系统目录
- [ ] **后台进程前端管理面板**：在 UI 中展示用户的后台进程列表，支持一键 kill
- [ ] **进程资源监控**：CPU / 内存使用量展示

## 依赖

- 前置：`ConversationSessionManager` 已存在（2026-06-24-conversation-lifecycle-plan）
- 新增依赖：`ZephyrConfigProperties.Shell` 配置类
