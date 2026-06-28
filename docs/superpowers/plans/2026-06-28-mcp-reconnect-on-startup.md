### Task 1: DDL — 三方言 Mapper XML 加列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/embedded/McpMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/postgresql/McpMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/mysql/McpMapper.xml`

**Interfaces:**
- Consumes: 无
- Produces: `zephyr_mcp_servers.reconnect_on_startup` 列（三方言一致）

- [ ] **Step 1: embedded 方言 DDL**

在 `createMcpServersTable` 的 `scope varchar(16) default 'user',` 行后添加：

```xml
      reconnect_on_startup smallint default 0,
```

三个文件完全一致的改动，在 `scope varchar(16) default 'user',` 和 `created_at bigint,` 之间插入新行。

`embedded/McpMapper.xml` 当前内容：
```xml
      status varchar(16) default 'disconnected',
      scope varchar(16) default 'user',
      created_at bigint,
      updated_at bigint
```

改为：
```xml
      status varchar(16) default 'disconnected',
      scope varchar(16) default 'user',
      reconnect_on_startup smallint default 0,
      created_at bigint,
      updated_at bigint
```

- [ ] **Step 2: postgresql 方言 DDL**

同上，`postgresql/McpMapper.xml` 做完全相同的改动。

- [ ] **Step 3: mysql 方言 DDL**

同上，`mysql/McpMapper.xml` 做完全相同的改动。

- [ ] **Step 4: 验证**

```bash
grep -n "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/embedded/McpMapper.xml
grep -n "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/postgresql/McpMapper.xml
grep -n "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/mysql/McpMapper.xml
```

预期：三个文件均有 `reconnect_on_startup` 行。

---

### Task 2: SQL 增量迁移

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

**Interfaces:**
- Consumes: Task 1 的 DDL 列定义
- Produces: 已有数据库实例的 `reconnect_on_startup` 列 + 存量数据同步

- [ ] **Step 1: zh-CN SQL**

在文件末尾追加：

```sql
ALTER TABLE zephyr_mcp_servers ADD COLUMN IF NOT EXISTS reconnect_on_startup smallint default 0;
UPDATE zephyr_mcp_servers SET reconnect_on_startup = 1 WHERE status = 'connected';
```

- [ ] **Step 2: en-US SQL**

同上，在文件末尾追加相同 SQL。

- [ ] **Step 3: ja-JP SQL**

同上，在文件末尾追加相同 SQL。

- [ ] **Step 4: 验证**

```bash
grep "reconnect_on_startup" src/main/resources/zephyr-zh-CN.sql
grep "reconnect_on_startup" src/main/resources/zephyr-en-US.sql
grep "reconnect_on_startup" src/main/resources/zephyr-ja-JP.sql
```

---

### Task 3: Entity 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpServerEntity.java`

**Interfaces:**
- Consumes: 无
- Produces: `McpServerEntity.reconnectOnStartup` (getter/setter 由 Lombok 生成)

- [ ] **Step 1: 加字段**

在 `private String status;` 后面添加：

```java
    private Integer reconnectOnStartup = 0;
```

- [ ] **Step 2: 验证**

```bash
grep "reconnectOnStartup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/entity/McpServerEntity.java
```

---

### Task 4: DAO + Mapper XML common

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

**Interfaces:**
- Consumes: Task 3 的 Entity 字段
- Produces: `mcpDao.queryServersToReconnect()` → `List<McpServerEntity>`, `mcpDao.updateReconnectOnStartup(id, value)`

- [ ] **Step 1: DAO 接口加方法**

在 `McpDao.java` 的 `queryConnectedServers()` 方法后添加：

```java
    List<McpServerEntity> queryServersToReconnect();
    void updateReconnectOnStartup(@Param("id") String id, @Param("reconnectOnStartup") Integer reconnectOnStartup);
```

- [ ] **Step 2: Mapper XML — 所有 SELECT 加列**

在 `common/McpMapper.xml` 中，每个 `select` 的列列表加 `reconnect_on_startup as reconnectOnStartup`。

需要修改的 SELECT 语句（共 6 个）：
- `queryServersByUserName` (line 6-10)
- `queryServerById` (line 34-39)
- `queryConnectedServers` (line 50-55)
- `querySharedServers` (line 123-130)
- `queryByNameAndScope` (line 177-183)

每个的列列表从：
```xml
               url, headers, status, scope,
               created_at as createdAt, updated_at as updatedAt
```

改为：
```xml
               url, headers, status, scope,
               reconnect_on_startup as reconnectOnStartup,
               created_at as createdAt, updated_at as updatedAt
```

- [ ] **Step 3: Mapper XML — INSERT 加列**

`insertServer` 的列和值各加 `reconnect_on_startup`：

列列表追加在 `scope,` 之后：
```xml
        insert into zephyr_mcp_servers (id, user_name, name, transport, command, args, env_vars, url, headers, status, scope, reconnect_on_startup, created_at, updated_at)
        values (#{id}, #{userName}, #{name}, #{transport}, #{command}, #{args}, #{envVars}, #{url}, #{headers}, #{status}, #{scope}, #{reconnectOnStartup}, #{createdAt}, #{updatedAt})
```

- [ ] **Step 4: Mapper XML — 新查询**

在 `</mapper>` 前添加两个新查询：

```xml
    <select id="queryServersToReconnect" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity">
        select id, user_name as userName, name, transport,
               command, args, env_vars as envVars,
               url, headers, status, scope,
               reconnect_on_startup as reconnectOnStartup,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_mcp_servers
        where reconnect_on_startup = 1
    </select>

    <update id="updateReconnectOnStartup">
        update zephyr_mcp_servers set reconnect_on_startup = #{reconnectOnStartup} where id = #{id}
    </update>
```

- [ ] **Step 5: 验证**

```bash
# 确认所有 SELECT 都包含了 reconnect_on_startup
grep -c "reconnect_on_startup" src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml
```

预期输出：`7`（5 个已有 SELECT + 1 个新 SELECT + 1 个 INSERT 列 = 7 处引用，UPDATE 不算 SELECT）

---

### Task 5: 清理 McpConnectionManager 死代码

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java`

**Interfaces:**
- Consumes: Task 4 的新 DAO 方法（`queryServersToReconnect` 替代 `queryConnectedServers`）
- Produces: 清理后的 `cleanupOrphanProcesses()`，不再有 `startupReconnectList`

- [ ] **Step 1: 删除字段和方法**

删除以下内容（约 lines 36-38 和 lines 111-113）：

```java
    // 删除：字段声明
    /** 启动时保存的待重连服务器列表（reset 前查询，供 McpServiceImpl.reconnectOnStartup() 使用） */
    private volatile List<McpServerEntity> startupReconnectList;

    // 删除：getter 方法
    /** 获取启动时需要重连的服务器列表 */
    public List<McpServerEntity> getStartupReconnectList() {
        return startupReconnectList != null ? startupReconnectList : List.of();
    }
```

- [ ] **Step 2: 简化 cleanupOrphanProcesses 的步骤1**

将 `cleanupOrphanProcesses()` 中标记为 `// 1. 记录重启前处于 connected 状态的服务器` 的整段代码（约 lines 49-56）：

```java
        // 1. 记录重启前处于 connected 状态的服务器
        try {
            startupReconnectList = mcpDao.queryConnectedServers();
            log.info("启动时发现 {} 个 connected 状态的 MCP 服务器，将尝试重连", startupReconnectList.size());
        } catch (Exception e) {
            log.warn("查询 connected 服务器列表失败", e);
            startupReconnectList = List.of();
        }
```

替换为：

```java
        // 1. 孤儿进程清理与状态重置（reconnect_on_startup 不会被重置）
```

原来的步骤 2（清理孤儿进程）和步骤 3（`resetAllServerStatus`）保持不变，但注释编号更新为 `// 2.` 和 `// 3.`。

- [ ] **Step 3: 验证死代码已清除**

```bash
grep -n "startupReconnectList\|getStartupReconnectList\|queryConnectedServers" src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java
```

预期：无输出（`queryConnectedServers` 在该文件中的调用也已清除）。

---

### Task 6: McpServiceImpl — 连接/断开/重连逻辑

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/service/impl/McpServiceImpl.java`

**Interfaces:**
- Consumes: Task 4 的 `mcpDao.queryServersToReconnect()` 和 `mcpDao.updateReconnectOnStartup()`
- Produces: 启动自动重连、UI 连接/断开写 `reconnect_on_startup`、`connect0()` 竞态守卫

- [ ] **Step 1: 修改 `connect()` — 手工连接成功后设置 flag**

当前 `connect()` (line 164-166):
```java
    @Override
    public void connect(String id, String userName) {
        connect0(id, userName, true);
    }
```

改为：
```java
    @Override
    public void connect(String id, String userName) {
        connect0(id, userName, true);
        mcpDao.updateReconnectOnStartup(id, 1);
        log.info("MCP reconnect_on_startup 已设为 1: id={}, user={}", id, userName);
    }
```

- [ ] **Step 2: 修改 `disconnect()` — 手工断开时清除 flag**

当前 `disconnect()` (lines 238-250) 在 `mcpDao.updateServerStatus(id, "disconnected", userName);` 前加一行：

```java
        mcpDao.updateReconnectOnStartup(id, 0);
```

即 `disconnect()` 方法体中在 `log.info` 后、`updateServerStatus` 前插入：

```java
        mcpDao.updateReconnectOnStartup(id, 0);
```

- [ ] **Step 3: 修改 `connect0()` — 加竞态守卫**

在 `connect0()` 方法体开头（permission check 之后、`log.info` 之前）加守卫：

```java
        // 重连路径：校验 reconnect_on_startup 仍为 1（防止启动重连与用户手工断开竞态）
        if (!checkPermission) {
            McpServerEntity current = mcpDao.queryServerById(id);
            if (current == null || current.getReconnectOnStartup() == null || current.getReconnectOnStartup() != 1) {
                log.info("MCP 重连跳过，reconnect_on_startup 已变更: id={}, user={}", id, userName);
                return;
            }
        }
```

放在 `checkPermission` 块之后、`log.info("MCP 开始连接...` 之前。

- [ ] **Step 4: 重写 `reconnectOnStartup()`**

当前 `reconnectOnStartup()` (lines 355-374)：
```java
    @Override
    public void reconnectOnStartup() {
        List<McpServerEntity> servers = connectionManager.getStartupReconnectList();
        ...
    }
```

改为直接查 DAO：
```java
    @Override
    public void reconnectOnStartup() {
        List<McpServerEntity> servers;
        try {
            servers = mcpDao.queryServersToReconnect();
        } catch (Exception e) {
            log.warn("查询待重连 MCP 服务器列表失败", e);
            return;
        }
        if (servers.isEmpty()) {
            log.info("无需要重连的 MCP 服务器");
            return;
        }
        log.info("开始重连 {} 个 MCP 服务器", servers.size());
        int ok = 0;
        for (McpServerEntity s : servers) {
            try {
                connect0(s.getId(), s.getUserName(), false);
                ok++;
                log.info("MCP 启动重连成功: server={}, user={}", s.getName(), s.getUserName());
            } catch (Exception e) {
                log.warn("MCP 启动重连失败: server={}, user={}, error={}",
                        s.getName(), s.getUserName(), e.getMessage());
            }
        }
        log.info("MCP 启动重连完成: 成功 {}/{}", ok, servers.size());
    }
```

原来的 `connectionManager.getStartupReconnectList()` 调用被替换为 `mcpDao.queryServersToReconnect()`。

- [ ] **Step 5: 删除 `McpConnectionManager` 的 import**

在 `McpServiceImpl.java` 中确认不再引用 `McpConnectionManager` 的 `import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;` 仍然有 `connectionManager` 字段使用，所以 **不删除**（`@Resource private McpConnectionManager connectionManager` 仍在使用中）。

---

### Task 7: 编译验证 + 端到端测试

- [ ] **Step 1: 编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn clean compile -q
```

预期：编译成功（无 `McpConnection`、`McpConnectionManager`、`McpServiceImpl`、`McpDao` 相关错误）。

- [ ] **Step 2: 启动服务**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 3: 测试 1 — 新建服务器默认不重连**

```bash
# 创建 MCP 服务器
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/mcp/server/create" \
  -d '{"name":"test-mcp","transport":"stdio","command":"echo","args":"hello"}'

# 查询状态，确认 reconnect_on_startup = 0
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/mcp/server/list"
```

预期：返回列表中 `test-mcp` 的 `reconnectOnStartup` 为 0，`status` 为 `disconnected`。

- [ ] **Step 4: 测试 2 — 手工连接后重启自动重连**

```bash
# 手工连接
SERVER_ID=$(curl -s -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/mcp/server/list" | python3 -c "import sys,json; print(json.load(sys.stdin)['body'][0]['id'])")
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/mcp/server/connect" \
  -d "{\"id\":\"$SERVER_ID\"}"

# 查询确认 reconnect_on_startup = 1, status = connected
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/mcp/server/list"

# 重启 zephyr（kill + 重新启动）
# 再次查询，确认自动重连成功（status = connected）
```

- [ ] **Step 5: 测试 3 — 手工断开后重启不自动重连**

同上，但在重启前先调 `disconnect` 接口，确认重启后 `status` 为 `disconnected` 且不自重连。

- [ ] **Step 6: 测试 4 — 迁移存量数据（如适用）**

如果数据库中有 `status = 'connected'` 的旧记录，启动后确认其 `reconnect_on_startup` 自动变为 1。
