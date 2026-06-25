# 代码去重与 workspace 缓存优化 实施计划

> **For agentic workers:** 使用此计划按任务逐步实施。步骤使用 checkbox (`- [ ]`) 语法跟踪。

**Goal:** 消除 `parseCommandList` 代码重复、统一 `executeShell` 和 `resolveWorkspaceBoundary` 的 workspace 路径获取方式为会话级缓存

**Architecture:** `SessionHandle` 加 `workspacePath` 字段，`send()` 入口一次解析，`dispatchTools` 和 `executeShell` 从 handle 取。`parseCommandList` 改为 public static 供 `ChatServiceImpl` 复用。

**Tech Stack:** Java 17, Spring Boot 3.5.4

## Global Constraints

- 不新建文件/类
- `mvn clean compile -q` 通过
- 现有功能不受影响
- workspace 缓存生命周期 = 单次消息周期，无需失效机制

---

### Task 1: SecurityEvaluator.parseCommandList 改为 public static

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java:164`

**Interfaces:**
- Produces: `public static Set<String> parseCommandList(String raw)` — 按逗号分割字符串为 Set

- [ ] **Step 1: 修改可见性**

`SecurityEvaluator.java` 第 164 行，`private static` → `public static`：

```java
// 修改前
private static Set<String> parseCommandList(String raw) {

// 修改后
public static Set<String> parseCommandList(String raw) {
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java
git commit -m "refactor: parseCommandList 改为 public static 供 ChatServiceImpl 复用"
```

---

### Task 2: SessionHandle 加 workspacePath 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java:32-33,93-106`

**Interfaces:**
- Consumes: 无
- Produces: `SessionHandle.getWorkspacePath(): String`, `register(String conversationId, String userName, String workspacePath): SessionHandle`

- [ ] **Step 1: SessionHandle 加字段和 getter**

`ConversationSessionManager.java`，在 `SessionHandle` 类中添加：

```java
public static class SessionHandle {
    private final String conversationId;
    private final String userName;
    private final String workspacePath;  // 新增
    volatile long lastActivityTime;
    volatile boolean cancelled;

    SessionHandle(String conversationId, String userName, String workspacePath) {
        this.conversationId = conversationId;
        this.userName = userName;
        this.workspacePath = workspacePath;  // 新增
        this.lastActivityTime = System.currentTimeMillis() / 1000;
    }

    public String getConversationId() { return conversationId; }
    public String getUserName() { return userName; }
    public String getWorkspacePath() { return workspacePath; }  // 新增
```

- [ ] **Step 2: register() 签名加 workspacePath 参数**

```java
// 修改前
public SessionHandle register(String conversationId, String userName) {
    SessionHandle handle = new SessionHandle(conversationId, userName);

// 修改后
public SessionHandle register(String conversationId, String userName, String workspacePath) {
    SessionHandle handle = new SessionHandle(conversationId, userName, workspacePath);
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean compile -q
```

Expected: 编译失败（`ChatServiceImpl.send()` 中 `register()` 调用仍需更新，这是预期的）

---

### Task 3: ChatServiceImpl 改造

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

**Interfaces:**
- Consumes: `SecurityEvaluator.parseCommandList(String): Set<String>`, `SessionHandle.getWorkspacePath(): String`
- Produces: 无新公共接口

改动点：
| 位置 | 改动 |
|------|------|
| ~93 | `register()` 调用前解析 workspacePath 并传入 |
| 407-426 | `resolveWorkspaceBoundary` 签名改为 `(String workspacePath)`，删 DB 查询 |
| 435 | 调用处改为 `resolveWorkspaceBoundary(handle.getWorkspacePath())` |
| 832-843 | `initShellWhitelist` 改为调用 `SecurityEvaluator.parseCommandList` |
| 877-885 | 删除 conversation + workspace DB 查询 |
| ~890 | `handle.getWorkspacePath()` 替代 `workspacePath` 变量 |

- [ ] **Step 1: 在 send() 中解析 workspace 路径**

在 `send()` 方法中，`register()` 调用前（当前 ~93 行位置），将：

```java
ConversationSessionManager.SessionHandle handle = sessionManager.register(cid, userName);
```

替换为：

```java
// 解析 workspace 路径
String workspacePath;
if (workspaceId != null && !workspaceId.isEmpty()) {
    WorkspaceEntity ws = workspaceDao.queryById(workspaceId);
    workspacePath = (ws != null && ws.getPath() != null && !ws.getPath().isBlank())
            ? ws.getPath()
            : System.getProperty("user.home");
} else {
    workspacePath = System.getProperty("user.home");
}

ConversationSessionManager.SessionHandle handle = sessionManager.register(cid, userName, workspacePath);
```

- [ ] **Step 2: 重构 resolveWorkspaceBoundary**

将 `resolveWorkspaceBoundary(String conversationId)` (第 407-426 行) 替换为：

```java
private WorkspaceBoundary resolveWorkspaceBoundary(String workspacePath) {
    if (workspacePath == null) return WorkspaceBoundary.NONE;
    try {
        Path wsPath = Path.of(workspacePath);
        try {
            return new WorkspaceBoundary(wsPath.toRealPath());
        } catch (IOException e) {
            log.warn("[安全] workspace 路径不存在或无法解析，使用规范化形式: {} ({})",
                    wsPath, e.getMessage());
            return new WorkspaceBoundary(wsPath.normalize().toAbsolutePath());
        }
    } catch (Exception e) {
        log.warn("[安全] 解析 workspace 路径失败: {} ({})",
                workspacePath, e.getMessage());
        return WorkspaceBoundary.NONE;
    }
}
```

- [ ] **Step 3: 更新 resolveWorkspaceBoundary 调用处**

`dispatchTools` 方法中（第 435 行），将：

```java
WorkspaceBoundary boundary = resolveWorkspaceBoundary(conversationId);
```

替换为：

```java
WorkspaceBoundary boundary = resolveWorkspaceBoundary(handle.getWorkspacePath());
```

- [ ] **Step 4: 复用 parseCommandList**

`initShellWhitelist()` 方法（第 832-843 行）替换为：

```java
@jakarta.annotation.PostConstruct
void initShellWhitelist() {
    shellWhitelist = SecurityEvaluator.parseCommandList(cfg.getShell().getAllowedCommands());
}
```

- [ ] **Step 5: executeShell 删除 DB 查询**

删除第 877-885 行：

```java
// 删除以下代码块
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
```

替换为：

```java
String workspacePath = handle.getWorkspacePath();
```

- [ ] **Step 6: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 代码级 grep 验证**

```bash
# resolveWorkspaceBoundary 不再查 DB
grep -A15 "resolveWorkspaceBoundary" src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java | grep -q "chatDao\|workspaceDao" && echo "FAIL" || echo "PASS: resolveWorkspaceBoundary"

# executeShell 不再查 DB
awk '/executeShell/,/^    \}/{print NR": "$0}' src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java | grep -q "chatDao\|workspaceDao" && echo "FAIL" || echo "PASS: executeShell"
```

Expected: Both PASS

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationSessionManager.java
git commit -m "refactor: workspace 路径会话级缓存，消除 parseCommandList 和 DB 查询重复"
```
