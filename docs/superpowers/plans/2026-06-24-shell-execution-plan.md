# execute_shell 内置工具实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 execute_shell 内置工具，让 LLM 能直接执行 shell 命令，前台进程会话级清理、后台进程用户级管理。

**Architecture:** ProcessSlot 机制消除 fork→register 竞争条件（先分配槽位再启动进程）；BackgroundProcessManager 与会话生命周期解耦，持有用户级后台进程；命令白名单作为程序化硬约束在 executeShell() 入口校验。

**Tech Stack:** Java 17, Spring Boot 3.5.4, ProcessHandle API, CopyOnWriteArrayList, ConcurrentHashMap

---

## 文件结构一览

| 文件 | 改动 | 职责 |
|------|------|------|
| `ZephyrConfigProperties.java` | 新增 `Shell` 内部类 | shell 模式、白名单、资源限制配置 |
| `application.yml` | 新增 `zephyr.shell.*` 节 | 对应配置默认值 |
| `ConversationSessionManager.java` | SessionHandle 新增 ProcessSlot + 方法 | 会话级进程追踪与清理 |
| `BackgroundProcessManager.java` | **新建** | 用户级后台进程管理 |
| `ContextBuilder.java` | 新增 3 个 buildXxxTool() 方法 | 注册 execute_shell/list_processes/kill_process 工具定义 |
| `ChatServiceImpl.java` | dispatchTools switch 加 3 个分支 + executeShell() + finally 加 killTrackedProcesses | 工具执行入口 + 会话结束时清理前台进程 |

---

### Task 1: ZephyrConfigProperties 新增 Shell 配置类

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 ZephyrConfigProperties 中添加 Shell 内部类和字段声明**

在 `ZephyrConfigProperties` 类中，紧跟 `private Encrypt encrypt = new Encrypt();` 之后（第 44 行附近），添加 shell 字段声明：

```java
/** Shell 命令执行相关配置 */
private Shell shell = new Shell();
```

在文件末尾（`Encrypt.Restful.Aes` 静态内部类结束后，`}` 之前），添加 Shell 静态内部类：

```java
// ================================================================
//  Shell 命令执行
// ================================================================

@Data
public static class Shell {
    /** Shell 执行模式: disabled | whitelist | allowAll，默认 whitelist */
    private String mode = "whitelist";
    /** whitelist 模式下允许的命令（仅命令名，不含参数） */
    private List<String> allowedCommands = List.of(
        // 解释器
        "python3", "python", "node", "ruby", "perl", "php", "lua", "deno", "bun",
        // 包管理
        "npm", "npx", "yarn", "pnpm", "pip", "pip3", "gem", "composer", "cargo", "go",
        // 版本控制
        "git", "hg",
        // 编译构建
        "javac", "java", "mvn", "gradle", "make", "cmake", "gcc", "g++", "clang", "clang++", "rustc", "dotnet",
        // 文件操作
        "ls", "cat", "head", "tail", "wc", "find", "grep", "egrep", "awk", "sed",
        "mkdir", "touch", "cp", "mv", "rm", "rmdir", "ln", "stat", "file", "du",
        "df", "tree", "realpath", "basename", "dirname",
        // 文本处理
        "sort", "uniq", "cut", "tr", "tee", "diff", "patch", "echo", "printf",
        "xargs", "envsubst", "column", "jq", "yq", "iconv", "strings", "od", "hexdump", "xxd",
        // 压缩归档
        "tar", "gzip", "gunzip", "zip", "unzip", "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd",
        // 网络（仅安全工具）
        "curl",
        // 系统信息
        "date", "env", "which", "whoami", "uname", "hostname", "uptime", "free", "vmstat", "iostat", "ulimit"
    );
    /** 每个用户最大后台进程数，默认 5 */
    private int maxBackgroundProcesses = 5;
    /** 后台进程最大运行时间（秒），超时自动 kill，默认 3600 */
    private int maxBackgroundLifetimeSeconds = 3600;
    /** 同步执行最大等待时间（秒），超时 destroyForcibly，默认 120 */
    private int commandTimeoutSeconds = 120;
}
```

- [ ] **Step 2: 在 application.yml 中添加 zephyr.shell 配置节**

在 `application.yml` 中，紧跟 `zephyr.encrypt` 节之后（第 301 行附近），添加：

```yaml
  shell:
    mode: whitelist  # disabled | whitelist | allowAll
    # allowedCommands 默认值已在 Java 代码中定义，此处仅展示可覆盖项
    max-background-processes: 5  # 每用户最大后台进程数
    max-background-lifetime-seconds: 3600  # 后台进程最大存活时间（秒）
    command-timeout-seconds: 120  # 同步命令超时（秒）
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java src/main/resources/application.yml
git commit -m "feat: ZephyrConfigProperties新增Shell配置类，支持命令白名单和资源限制"
```

---

### Task 2: ConversationSessionManager 新增 ProcessSlot 机制

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java`

- [ ] **Step 1: 在 SessionHandle 中添加 ProcessSlot 内部类和 trackedProcesses 字段**

在 `SessionHandle` 类中（`cancel()` 方法之前），添加：

```java
private final List<ProcessSlot> trackedProcesses = new CopyOnWriteArrayList<>();

public ProcessSlot reserveProcessSlot(String command) {
    ProcessSlot slot = new ProcessSlot(command);
    trackedProcesses.add(slot);
    return slot;
}

public void killTrackedProcesses() {
    for (ProcessSlot s : trackedProcesses) {
        if (s.state == ProcessSlot.State.BOUND) {
            try {
                ProcessHandle.of(s.pid).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.destroyForcibly();
                });
                log.info("[会话] 清理进程 cid={}, pid={}, cmd={}", conversationId, s.pid, s.command);
            } catch (Exception e) {
                log.warn("[会话] 清理进程失败 cid={}, pid={}", conversationId, s.pid, e);
            }
        }
    }
    trackedProcesses.clear();
}
```

需要在文件顶部添加 import：

```java
import java.util.concurrent.CopyOnWriteArrayList;
```

- [ ] **Step 2: 添加 ProcessSlot 静态内部类**

在 `SessionHandle` 类定义之后、`CancelSessionException` 类之前，添加：

```java
public static class ProcessSlot {
    enum State { RESERVED, BOUND, FAILED }

    final String command;
    volatile State state = State.RESERVED;
    volatile long pid;

    ProcessSlot(String command) {
        this.command = command;
    }

    void bind(Process p) {
        this.pid = p.pid();
        this.state = State.BOUND;
    }

    void markFailed() {
        this.state = State.FAILED;
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java
git commit -m "feat: SessionHandle新增ProcessSlot机制，消除fork-register竞争条件"
```

---

### Task 3: 新建 BackgroundProcessManager

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/BackgroundProcessManager.java`

- [ ] **Step 1: 创建 BackgroundProcessManager**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class BackgroundProcessManager {

    @Resource
    private ZephyrConfigProperties cfg;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, TrackedProcess>> userProcesses = new ConcurrentHashMap<>();

    @Data
    public static class TrackedProcess {
        private final long pid;
        private final String userName;
        private final String command;
        private final String workspaceId;
        private final Instant startedAt;
    }

    public long register(String userName, Process p, String command, String workspaceId) {
        long pid = p.pid();
        TrackedProcess tp = new TrackedProcess(pid, userName, command, workspaceId, Instant.now());
        userProcesses.computeIfAbsent(userName, k -> new ConcurrentHashMap<>()).put(pid, tp);
        log.info("[后台进程] 注册 user={}, pid={}, cmd={}", userName, pid, command);
        return pid;
    }

    public List<TrackedProcess> list(String userName) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m == null) return List.of();
        return new ArrayList<>(m.values());
    }

    public boolean kill(String userName, long pid) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m == null) return false;
        TrackedProcess tp = m.remove(pid);
        if (tp == null) return false;
        try {
            ProcessHandle.of(pid).ifPresent(ph -> {
                ph.descendants().forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            });
            cleanupLogFile(tp);
            log.info("[后台进程] 已终止 user={}, pid={}, cmd={}", userName, pid, tp.getCommand());
            return true;
        } catch (Exception e) {
            log.warn("[后台进程] 终止失败 user={}, pid={}", userName, pid, e);
            return false;
        }
    }

    public void killAll(String userName) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.remove(userName);
        if (m == null) return;
        log.info("[后台进程] 批量终止 user={}, 数量={}", userName, m.size());
        for (TrackedProcess tp : m.values()) {
            try {
                ProcessHandle.of(tp.getPid()).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.destroyForcibly();
                });
                cleanupLogFile(tp);
            } catch (Exception e) {
                log.warn("[后台进程] 终止失败 pid={}", tp.getPid(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "#{@zephyrConfigProperties.shell.maxBackgroundLifetimeSeconds * 1000}")
    public void enforceLimits() {
        long lifetimeSec = cfg.getShell().getMaxBackgroundLifetimeSeconds();
        Instant cutoff = Instant.now().minusSeconds(lifetimeSec);
        userProcesses.forEach((user, processes) -> {
            List<TrackedProcess> expired = new ArrayList<>();
            for (TrackedProcess tp : processes.values()) {
                if (tp.getStartedAt().isBefore(cutoff)) {
                    expired.add(tp);
                }
            }
            for (TrackedProcess tp : expired) {
                kill(user, tp.getPid());
                log.info("[后台进程] 超时清理 user={}, pid={}, cmd={}", user, tp.getPid(), tp.getCommand());
            }
        });
    }

    public void enforceQuota(String userName) {
        int max = cfg.getShell().getMaxBackgroundProcesses();
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m != null && m.size() >= max) {
            throw new RuntimeException("后台进程数已达上限 " + max + "，请先使用 kill_process 终止旧进程");
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[后台进程] 应用关闭，终止所有后台进程");
        for (String user : userProcesses.keySet()) {
            killAll(user);
        }
    }

    private void cleanupLogFile(TrackedProcess tp) {
        try {
            Path logFile = Paths.get(tp.getWorkspaceId(), ".zephyr-logs", tp.getPid() + ".log");
            Files.deleteIfExists(logFile);
        } catch (Exception e) {
            log.debug("[后台进程] 清理日志文件失败 pid={}", tp.getPid());
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/BackgroundProcessManager.java
git commit -m "feat: 新增BackgroundProcessManager，用户级后台进程管理"
```

---

### Task 4: ContextBuilder 注册三个新内置工具

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

- [ ] **Step 1: 在 build() 方法中注册三个新工具**

在 `ContextBuilder.build()` 方法中，紧跟 `toolDefs.add(buildSearchKnowledgeTool());`（第 219 行）之后，添加：

```java
toolDefs.add(buildExecuteShellTool());
toolDefs.add(buildListProcessesTool());
toolDefs.add(buildKillProcessTool());
```

- [ ] **Step 2: 添加三个 buildXxxTool() 方法**

在 `buildSearchKnowledgeTool()` 方法之后（第 454 行附近，`@Data public static class Context` 之前），添加：

```java
private ToolDef buildExecuteShellTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("command", Map.of("type", "string", "description", "要执行的完整命令字符串"));
    props.put("background", Map.of("type", "boolean", "description", "是否后台运行，默认 false"));

    return ToolDef.builder()
            .type("function")
            .function(ToolDef.FunctionDef.builder()
                    .name("execute_shell")
                    .description("在工作空间目录执行 shell 命令。前台命令（默认）阻塞等待结果返回；后台命令（background=true）立即返回，进程跨会话存活。")
                    .parameters(Map.of("type", "object", "properties", props, "required", List.of("command")))
                    .build())
            .build();
}

private ToolDef buildListProcessesTool() {
    return ToolDef.builder()
            .type("function")
            .function(ToolDef.FunctionDef.builder()
                    .name("list_processes")
                    .description("列出当前用户的所有后台进程")
                    .parameters(Map.of("type", "object", "properties", new LinkedHashMap<>()))
                    .build())
            .build();
}

private ToolDef buildKillProcessTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("pid", Map.of("type", "integer", "description", "进程 PID"));

    return ToolDef.builder()
            .type("function")
            .function(ToolDef.FunctionDef.builder()
                    .name("kill_process")
                    .description("终止指定后台进程")
                    .parameters(Map.of("type", "object", "properties", props, "required", List.of("pid")))
                    .build())
            .build();
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: ContextBuilder注册execute_shell/list_processes/kill_process三个内置工具"
```

---

### Task 5: ChatServiceImpl 实现 execute_shell 逻辑

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: 添加 BackgroundProcessManager 依赖注入**

在 `ChatServiceImpl` 类中，紧跟 `@Resource private ConversationSessionManager sessionManager;`（第 64 行）之后，添加：

```java
@Resource
private BackgroundProcessManager backgroundProcessManager;
```

添加 import：

```java
import com.github.hbq969.ai.zephyr.chat.service.BackgroundProcessManager;
```

- [ ] **Step 2: 在 send() 的 finally 块中添加 killTrackedProcesses 调用**

将 `send()` 方法中 `finally` 块内（第 265-271 行）的：

```java
} finally {
    if (!completed) {
        try { emitter.complete(); } catch (Exception ignored) {}
    }
    log.info("[会话] 异步任务结束 cid={}, completed={}", cid, completed);
    sessionManager.remove(cid);
}
```

修改为：

```java
} finally {
    if (!completed) {
        try { emitter.complete(); } catch (Exception ignored) {}
    }
    handle.killTrackedProcesses();
    log.info("[会话] 异步任务结束 cid={}, completed={}", cid, completed);
    sessionManager.remove(cid);
}
```

- [ ] **Step 3: 在 dispatchTools() 的 switch 中添加 execute_shell 分支**

在 `dispatchTools()` 方法的 switch 中（第 387 行附近），紧跟 `case "search_knowledge"` 分支之后、`default` 之前，添加三个新 case：

```java
case "execute_shell" -> executeShell(tc.getArguments(), userName, workspaceId(conversationId));
case "list_processes" -> listProcesses(userName);
case "kill_process" -> killProcess(tc.getArguments(), userName);
```

注意：`workspaceId(conversationId)` 需要从 `conversationId` 推导出 workspace 路径。`execute_shell` 在 `dispatchTools` 的 switch 中被调用，但此时 `conversationId` 在 `send()` 方法的局部变量中不可直接访问。需要修改 dispatchTools 的签名来传递 workspace 信息。

修改方案：`dispatchTools` 方法中从 `toolCalls` 的调用上下文无法直接拿到 conversationId/workspaceId。改用 **延迟处理**——在 `dispatchTools` switch 中不拿到 workspace，而是把 cid 传进来并用于 workspace 解析。

将 `dispatchTools` 签名改为：

```java
private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls, String userName, List<String> enabledKbIds, String conversationId)
```

并在 `send()` 方法调用处（第 204 行）改为：

```java
List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName, enabledKbIds, cid);
```

switch 中 `execute_shell` 改为：

```java
case "execute_shell" -> executeShell(tc.getArguments(), userName, conversationId);
```

- [ ] **Step 4: 实现 executeShell 方法**

在 `ChatServiceImpl` 类中添加：

```java
private String executeShell(Map<String, Object> args, String userName, String conversationId) {
    String mode = cfg.getShell().getMode();
    if ("disabled".equals(mode)) {
        return "Shell 命令执行已禁用";
    }

    String command = args.get("command").toString().trim();
    if (command.isEmpty()) {
        return "命令不能为空";
    }

    boolean background = args.containsKey("background") && Boolean.TRUE.equals(args.get("background"));

    // 命令白名单校验
    if (!"allowAll".equals(mode)) {
        String cmdName = command.split("\\s+", 2)[0];
        // 去掉路径前缀（如 /usr/bin/python3 → python3）
        int lastSlash = cmdName.lastIndexOf('/');
        if (lastSlash >= 0) {
            cmdName = cmdName.substring(lastSlash + 1);
        }
        if (!cfg.getShell().getAllowedCommands().contains(cmdName)) {
            return "命令 '" + cmdName + "' 不在白名单中，拒绝执行";
        }
    }

    // 获取工作空间路径
    String workspacePath = System.getProperty("user.home");
    ConversationEntity conv = chatDao.queryConversationById(conversationId);
    if (conv != null && conv.getWorkspaceId() != null) {
        com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws =
                workspaceDao.queryById(conv.getWorkspaceId());
        if (ws != null) {
            workspacePath = ws.getPath();
        }
    }

    // 获取 conversationId 对应的 SessionHandle（当前线程就是此会话的异步线程，
    // handle 已在外层 send() 中 register，所以一定能 get 到）
    ConversationSessionManager.SessionHandle handle = sessionManager.get(conversationId);
    if (handle == null) {
        return "会话不存在，无法执行命令";
    }

    if (background) {
        // 后台模式：检查配额 → 启动进程 → 注册到 BackgroundProcessManager
        backgroundProcessManager.enforceQuota(userName);
        try {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .directory(new java.io.File(workspacePath))
                    .redirectErrorStream(true)
                    .start();
            long pid = backgroundProcessManager.register(userName, p, command, workspacePath);
            return "PID: " + pid + ", 日志: " + workspacePath + "/.zephyr-logs/" + pid + ".log";
        } catch (Exception e) {
            return "后台命令启动失败: " + e.getMessage();
        }
    } else {
        // 前台模式：ProcessSlot → 启动 → 等待 → 返回结果
        ConversationSessionManager.ProcessSlot slot = handle.reserveProcessSlot(command);
        try {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .directory(new java.io.File(workspacePath))
                    .redirectErrorStream(true)
                    .start();
            slot.bind(p);

            int timeout = cfg.getShell().getCommandTimeoutSeconds();
            boolean timeoutReached = !p.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
            if (timeoutReached) {
                p.destroyForcibly();
                return "命令超时（" + timeout + "s），已终止";
            }

            String output = new String(p.getInputStream().readAllBytes());
            return "退出码: " + p.exitValue() + "\n" + output;
        } catch (Exception e) {
            slot.markFailed();
            return "命令执行异常: " + e.getMessage();
        }
    }
}
```

- [ ] **Step 5: 实现 listProcesses 方法**

```java
private String listProcesses(String userName) {
    List<BackgroundProcessManager.TrackedProcess> list = backgroundProcessManager.list(userName);
    if (list.isEmpty()) {
        return "当前没有后台进程";
    }
    StringBuilder sb = new StringBuilder("后台进程列表:\n");
    for (BackgroundProcessManager.TrackedProcess tp : list) {
        sb.append("PID: ").append(tp.getPid())
          .append(", 命令: ").append(tp.getCommand())
          .append(", 启动时间: ").append(tp.getStartedAt())
          .append("\n");
    }
    return sb.toString();
}
```

- [ ] **Step 6: 实现 killProcess 方法**

```java
private String killProcess(Map<String, Object> args, String userName) {
    long pid = ((Number) args.get("pid")).longValue();
    boolean killed = backgroundProcessManager.kill(userName, pid);
    return killed ? "进程 " + pid + " 已终止" : "进程 " + pid + " 未找到或已结束";
}
```

- [ ] **Step 7: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: ChatServiceImpl实现execute_shell/list_processes/kill_process工具分发"
```

---

## 依赖图

```
Task 1 (ZephyrConfigProperties.Shell) ──┐
                                        ├──> Task 5 (ChatServiceImpl 消费配置)
                                        │
Task 2 (ProcessSlot) ───────────────────┤
                                        ├──> Task 5 (ChatServiceImpl 使用 ProcessSlot + BackgroundProcessManager)
                                        │
Task 3 (BackgroundProcessManager) ──────┤
                                        │
Task 4 (ContextBuilder 注册工具) ────────┤
                                        │
Task 5 (ChatServiceImpl 消费前面全部) <──┘
```

Task 1-4 之间无依赖，可并行执行。Task 5 依赖 Task 1-4 全部完成。
