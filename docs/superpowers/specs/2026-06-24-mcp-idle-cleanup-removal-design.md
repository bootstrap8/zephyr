# MCP 空闲清理移除

## 背景

`2026-06-23-mcp-connection-lifecycle-design.md` 引入了空闲连接自动回收（`cleanupIdle()` 定时任务 + LRU 淘汰 `evictLru()`）。实际使用中发现自动回收破坏了用户体验：空闲超时后连接被悄悄关闭，DB 状态却未同步更新（已在上一轮修复），且用户期望 MCP 连接只在自己主动断开或程序退出时才销毁。

## 方案

**移除：** 空闲清理定时任务、LRU 淘汰。配置项保留不动。

**保留两种销毁路径：** 页面手工触发 + `@PreDestroy` 程序退出。

### 1. McpConnectionManager.java

| 操作 | 说明 |
|------|------|
| 删除 `cleanupIdle()` | 包括 `@Scheduled` 注解 |
| 删除 `evictLru()` | 不再静默淘汰 |
| 删除 `updateDbStatus()` | 上一轮加的辅助方法，只被上述两个方法调用 |
| 新增 `@PreDestroy destroy()` | 遍历所有连接，逐个 `close()` 并更新 DB 状态为 `disconnected` |
| 修改 `createConnection()` | 连接数超 `maxConnections` 时直接抛 `RuntimeException`，不再 LRU 淘汰 |

### 2. ZephyrConfigProperties.java

保留 `idleTimeoutMillis`、`cleanupIntervalMillis` 字段不变。虽暂无代码引用，但保留配置项以备将来使用。

### 3. application.yml

保留对应配置项不变。

### 4. 保留不变

- `disconnect()` 页面手工断开（`McpServiceImpl.disconnect()` → `removeConnection()` + DB 更新）
- `cleanupOrphanProcesses()` 启动时孤儿进程清理
- `maxConnections` 上限（超限抛异常而非淘汰）
- `McpConnection.close()` 进程 kill + PID 文件清理逻辑

## 影响范围

| 文件 | 改动 |
|------|------|
| `McpConnectionManager.java` | 删除 3 个方法，新增 1 个 `@PreDestroy`，修改 `createConnection()` |
| `ZephyrConfigProperties.java` | 不变 |
| `application.yml` | 不变 |
