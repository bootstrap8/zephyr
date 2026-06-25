# 文件系统安全确认弹窗集成 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 SecurityEvaluator 中新增 workspace 边界检查，让 LLM 调用 write_file/edit_file 访问 workspace 外路径时自动弹出确认窗口。

**Architecture:** SecurityEvaluator 新增 WorkspaceBoundary 值对象接收预解析的 workspace 路径（由 ChatServiceImpl 循环外一次性解析），evaluateFileWrite() 在 bypass 检查之后、mode 判断之前插入边界检查。Prompt 模板增加说明告知 LLM 直接调工具即可。

**Tech Stack:** Java 17, Spring Boot 3.5.4, java.nio.file.Path

## Global Constraints

- 不可改动前端 `ConfirmDialog.vue`
- 不可改动 shell 命令评估逻辑
- bypass 模式完全不受影响
- SecurityEvaluator 不注入 ChatDao/WorkspaceDao

---

### Task 1: SecurityEvaluator — 新增 WorkspaceBoundary + 边界检查

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java`

**Interfaces:**
- Produces: `WorkspaceBoundary` record (public, inside SecurityEvaluator), updated `evaluate()` signature accepting `WorkspaceBoundary`, updated `evaluateFileWrite()` with boundary check

- [ ] **Step 1: 新增 WorkspaceBoundary 值对象**

在 SecurityEvaluator.java 的 Decision/Result 定义之后添加：

```java
    /**
     * workspace 边界信息，用于文件写入路径检查。
     * 纯值对象，零依赖。
     */
    public record WorkspaceBoundary(Path path) {
        public static final WorkspaceBoundary NONE = new WorkspaceBoundary(null);

        public boolean isPresent() { return path != null; }

        public boolean contains(Path target) {
            return isPresent() && target.startsWith(path);
        }
    }
```

需要新增 import：`import java.nio.file.Path;`

- [ ] **Step 2: 修改 evaluate() 签名**

将：

```java
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode) {
```

改为：

```java
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                           WorkspaceBoundary boundary) {
```

同时更新 switch 分支，`evaluateFileWrite` 传入 boundary：

```java
        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments, mode);
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> Result.allow();
        };
```

- [ ] **Step 3: 重写 evaluateFileWrite() 方法**

将现有的 `evaluateFileWrite(Map<String, Object> arguments, String mode)` 替换为：

```java
    private Result evaluateFileWrite(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
        String filePath = arguments.getOrDefault("file_path", "").toString();
        if (filePath.isEmpty()) {
            filePath = arguments.getOrDefault("filePath", "").toString();
        }

        // HARD BLOCK：修改安全 prompt 文件（大小写不敏感）
        String lowerPath = filePath.toLowerCase();
        for (String prefix : HARD_BLOCK_PATH_PREFIXES) {
            if (lowerPath.contains(prefix)) {
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml
        if (filePath.endsWith("application.yml") || filePath.endsWith("application-me.yml")
                || filePath.endsWith("application-prod.yml")) {
            return Result.block("HARD_BLOCK", "禁止修改应用配置文件: " + filePath);
        }

        // bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            return Result.allow();
        }

        // workspace 边界检查
        if (boundary.isPresent()) {
            Path targetPath = Path.of(filePath);
            if (!targetPath.isAbsolute()) {
                targetPath = boundary.path().resolve(targetPath);
            }
            targetPath = targetPath.normalize();
            try {
                targetPath = targetPath.toRealPath();
            } catch (IOException ignored) {
                // symlink 解析失败，用 normalize 结果
            }

            if (!boundary.contains(targetPath)) {
                return Result.confirm("WORKSPACE_BOUNDARY",
                        "目标路径 " + filePath + " 不在工作空间 " + boundary.path() + " 内");
            }
        }

        // default 模式：所有文件编辑需确认
        if (!"acceptEdits".equalsIgnoreCase(mode)) {
            return Result.confirm("MODE_DEFAULT", "Default 模式下文件写入需要用户确认");
        }

        // acceptEdits 模式：workspace 内文件编辑自动放行
        return Result.allow();
    }
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java
git commit -m "feat: SecurityEvaluator 新增 WorkspaceBoundary 和文件写入 workspace 边界检查"
```

---

### Task 2: ChatServiceImpl — resolveWorkspaceBoundary + 传入 boundary

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

**Interfaces:**
- Consumes: `SecurityEvaluator.WorkspaceBoundary` (from Task 1), `SecurityEvaluator.evaluate(toolName, arguments, userName, mode, boundary)` (updated signature from Task 1)
- Produces: `resolveWorkspaceBoundary()` private helper method

- [ ] **Step 1: 新增 import**

在 import 区域添加：

```java
import com.github.hbq969.ai.zephyr.security.SecurityEvaluator.WorkspaceBoundary;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
```

`ChatDao`、`WorkspaceDao`、`ConversationEntity` 已经 import（分别在行 5、6、19），无需重复添加。

- [ ] **Step 2: 新增 resolveWorkspaceBoundary() 方法**

在 `dispatchTools()` 方法之前添加：

```java
    private WorkspaceBoundary resolveWorkspaceBoundary(String conversationId) {
        if (conversationId == null) return WorkspaceBoundary.NONE;
        try {
            ConversationEntity conv = chatDao.queryConversationById(conversationId);
            if (conv == null || conv.getWorkspaceId() == null) return WorkspaceBoundary.NONE;
            WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
            if (ws == null || ws.getPath() == null) return WorkspaceBoundary.NONE;
            Path wsPath = Path.of(ws.getPath());
            if (!Files.exists(wsPath)) {
                log.warn("[安全] workspace 路径不存在，使用规范化形式: {}", wsPath);
                return new WorkspaceBoundary(wsPath.normalize().toAbsolutePath());
            }
            return new WorkspaceBoundary(wsPath.toRealPath());
        } catch (IOException e) {
            log.warn("[安全] 解析 workspace 路径失败 conv={}: {}", conversationId, e.getMessage());
            return WorkspaceBoundary.NONE;
        }
    }
```

- [ ] **Step 3: 修改 dispatchTools() 中的 evaluate() 调用**

在 `dispatchTools()` 的 for 循环之前加一行 workspace 解析：

```java
        List<Map<String, Object>> results = new ArrayList<>();

        // === 一次性解析 workspace 边界（循环外，避免 N 次 DB 查询） ===
        WorkspaceBoundary boundary = resolveWorkspaceBoundary(conversationId);

        for (LlmResult.ToolCall tc : toolCalls) {

            // === 安全评估 ===
            SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
                    tc.getName(), tc.getArguments(), userName, mode, boundary);
```

即：在 `List<Map<String, Object>> results = new ArrayList<>();` 之后、`for` 循环之前插入 `WorkspaceBoundary boundary = resolveWorkspaceBoundary(conversationId);`，然后修改 `securityEvaluator.evaluate(...)` 调用加 `boundary` 参数。

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: dispatchTools 循环外解析 workspace boundary 传入 SecurityEvaluator"
```

---

### Task 3: Prompt 模板更新

**Files:**
- Modify: `src/main/resources/prompts/modes/default.md`
- Modify: `src/main/resources/prompts/modes/accept-edits.md`

**Interfaces:**
- 无代码接口依赖，纯文本更新

- [ ] **Step 1: 更新 default.md**

在文件末尾（"workspace 外访问" 那行之后）添加系统自动确认说明：

将：

```markdown
- **workspace 外访问**：需用户明确授权后方可执行。即使用户在消息中指定了 workspace 外的路径，也必须先征得授权，不得直接执行
```

替换为：

```markdown
- **workspace 外访问**：需用户明确授权后方可执行。即使用户在消息中指定了 workspace 外的路径，也必须先征得授权，不得直接执行
- **系统自动确认**：调用 write_file / edit_file 时，SecurityEvaluator 自动检查 workspace 边界。即使用户在消息中指定了 workspace 外的路径，也直接调用工具，系统会自动弹出确认窗口征求用户授权。不要在文字中反复询问用户"可以吗"
```

- [ ] **Step 2: 更新 accept-edits.md**

同上，将：

```markdown
- **workspace 外访问**：需用户明确授权后方可执行。即使用户在消息中指定了 workspace 外的路径，也必须先征得授权，不得直接执行
```

替换为：

```markdown
- **workspace 外访问**：需用户明确授权后方可执行。即使用户在消息中指定了 workspace 外的路径，也必须先征得授权，不得直接执行
- **系统自动确认**：调用 write_file / edit_file 时，SecurityEvaluator 自动检查 workspace 边界。即使你处于 acceptEdits 模式，访问 workspace 外的路径仍会自动弹出确认窗口。直接调用工具即可，不要在文字中反复询问用户
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/prompts/modes/default.md src/main/resources/prompts/modes/accept-edits.md
git commit -m "feat: fileSystemSecurity prompt 模板增加系统自动确认弹窗说明"
```

---

### 验证清单

编译通过后：

1. 启动后端：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && cp -rf src/main/resources/*.yml target/classes/ && cp -rf src/main/resources/*.xml target/classes/ && mvn spring-boot:run -Dspring-boot.run.profiles=me`
2. default 模式下发消息 "请在 /tmp/test.txt 写入 hello"
3. 确认前端弹出 ConfirmDialog，rule=`WORKSPACE_BOUNDARY`，toolName=`write_file`
4. 点击"允许本次"→ 执行
5. 点击"拒绝"→ 返回拒绝消息
6. acceptEdits 模式 workspace 内写入 → 不弹窗
7. acceptEdits 模式 workspace 外写入 → 弹窗
8. bypass 模式 → 不弹窗
