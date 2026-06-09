# MCP 工具运行时调用 设计规格

## 概述

扩展 McpClient，在现有 `discoverTools()`（tools/list）基础上增加 `callTool()`（tools/call），新增连接管理器维护 MCP server 的长连接生命周期。

## 连接管理器

### McpConnectionManager

```
McpConnectionManager
├── key = userName + ":" + serverId
├── 懒初始化（首次 tool call 时建连）
├── 空闲 15 分钟自动回收（后台每 5 分钟扫描）
├── 全局上限 100，超限 LRU 淘汰最久未用
├── stdio: 独立子进程（Process + reader/writer）
├── HTTP: 独立 session（Mcp-Session-Id）
└── 服务重启后全部重置，靠懒初始化重建
```

### 连接状态机

```
        懒初始化触发
unconnected ──────────→ connecting ──→ connected
     ↑                      │              │
     │                      ↓              │ 空闲 15min / delete server / disconnect
     └────── disconnected ←─┴──────────────┘
```

### 容量估算

| 参数 | 值 |
|------|----|
| 注册用户 | 30 |
| 同时在线 | ~10-15 |
| 实际触发 tool call | ~5-8 |
| 峰值并发连接 | 40-60 |
| **连接上限** | **100** |
| **空闲回收** | **15 分钟** |
| **扫描间隔** | **5 分钟** |
| **淘汰策略** | **LRU** |

## McpClient 扩展

### callTool(connection, toolName, arguments) → String

**stdio:**
```
JSON-RPC request: {jsonrpc:"2.0", id:N, method:"tools/call", params:{name:"x", arguments:{...}}}
  ↓ stdin → Process → stdout ↓
JSON-RPC response: {jsonrpc:"2.0", id:N, result:{content:[{type:"text", text:"..."}]}}
  → 提取 result.content[0].text 返回
```

**HTTP:**
```
POST {url} + Mcp-Session-Id header
Body: {jsonrpc:"2.0", id:N, method:"tools/call", params:{name:"x", arguments:{...}}}
  → 解析 SSE/JSON 响应中的 result.content
```

## 工具调用流程（配合 LLM）

```
1. LLM 返回: {role:"assistant", tool_calls:[{id:"tc1", function:{name:"search", arguments:{query:"..."}}}]}
2. ChatService 解析 tool_calls
3. 匹配 toolName → (serverId, serverType)
4. McpConnectionManager.getConnection(userName, serverId) → 连接
5. McpClient.callTool(connection, "search", {query:"..."}) → "查询结果..."
6. 构造 tool role message:
   {role:"tool", tool_call_id:"tc1", content:"查询结果..."}
7. 追加到 messages，再次调 LLM
8. LLM 可能再次返回 tool_calls → 循环（最多 10 轮）
```

## 新增/修改文件

```
mcp/
├── utils/
│   ├── McpClient.java              # 修改：新增 callTool()
│   ├── McpConnectionManager.java   # 🆕 连接管理器
│   └── McpConnection.java          # 🆕 连接抽象（接口 + StdioImpl + HttpImpl）
├── service/impl/McpServiceImpl.java # 修改：connect/disconnect 对接管理器
```

## 安全要求

- 工具调用校验 userName + serverId，禁止跨用户
- 工具调用超时 30 秒，超时中断并返回错误
- stdio 进程异常退出自动标记 disconnected
- HTTP 调用失败不 crash，返回错误信息给 LLM

## 备注

- McpConnectionManager 需要 `@Component` + `@Scheduled` 定时回收
- 连接清理：用户在 settings 中 disconnect → 主动关闭；delete server → 级联关闭
- 工具调用结果先不持久化（由消息持久化统一处理 tool_calls_json 字段）
