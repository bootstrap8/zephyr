# 内置工具角色管控设计

## 概述

zephyr 作为多租户 Web Agent 应用，内置 6 个工具（`use_skill`、`use_memory`、`search_knowledge`、`execute_shell`、`list_processes`、`kill_process`），其中 shell 族工具（`execute_shell`、`list_processes`、`kill_process`）风险最高。当前这些工具对所有角色同等开放，需要为 shell 族工具增加 admin/non-admin 角色管控。

## 决策摘要

| 决策项 | 选择 | 不选的方案及原因 |
|--------|------|-----------------|
| 管控粒度 | 按角色（admin vs non-admin） | 全局一刀切：太粗，未来多角色扩展无空间 |
| 管控范围 | shell 族 3 个工具 | 全部 6 个：use_skill/use_memory/search_knowledge 风险低，过度限制削弱可用性 |
| 拦截方式 | 工具仍传给 LLM，后端执行层拦截 | 不传工具定义：改动 ContextBuilder 侵入性更大，且 LLM 无法规划 shell 方案来诊断问题 |
| 配置入口 | admin 全局设置页 | YAML 配置：需重启；复用 SecuritySettings 表：语义混杂 |
| 拦截消息 | "命令未执行（无权限）"（静默拒绝） | 明确拒绝：可能触发 LLM 反复尝试；模糊拒绝：用户困惑 |
| 配置存储 | 独立新表 `zephyr_builtin_tool_controls` | 复用 `zephyr_security_rules`：规则类型和 UI 形态不匹配 |

## 数据模型

### 新表 `zephyr_builtin_tool_controls`

```sql
create table if not exists zephyr_builtin_tool_controls (
    tool_name      varchar(64)   primary key,
    description    varchar(512)  default '',
    require_admin  int           default 1,
    created_at     bigint,
    updated_at     bigint
);
```

| 列 | 说明 |
|---|---|
| `tool_name` | 内置工具名，PK |
| `description` | 工具描述，设置页展示 |
| `require_admin` | 1=仅管理员可用，0=所有角色可用 |

### 种子数据

```sql
insert into zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
values
  ('execute_shell',  '在工作空间目录执行任意 shell 命令，支持前台阻塞和后台运行', 1, unix_timestamp(), unix_timestamp()),
  ('list_processes', '列出当前用户启动的所有后台进程及其 PID',                     1, unix_timestamp(), unix_timestamp()),
  ('kill_process',   '根据 PID 终止指定的后台进程',                               1, unix_timestamp(), unix_timestamp());
```

所有工具默认 `require_admin=1`（仅管理员可用）。

## 执行流程

### 安全防线（按执行顺序）

```
第 0 层 [增强]  SecurityEvaluator.evaluate()
                 ├─ 角色权限检查（新增，shell 族工具入口即判断）
                 └─ HARD_BLOCK / SOFT_BLOCK 模式匹配（已有）
第 1 层 [已有]  workspace boundary                → 路径边界检查
第 2 层 [已有]  executeShell() / executeCommand()  → 白名单过滤
```

角色检查放在 `SecurityEvaluator.evaluate()` 内部而非 `ChatServiceImpl.dispatchTools()`，原因：
- 复用已有的 BLOCK 流水线（`dispatchTools()` 中 `secResult.decision() == BLOCK` 的处理路径，包含 tool_call_id、审计日志）
- 避免创建并行安全路径，减少维护成本
- 防线集中在一处，安全审计一目了然

### 拦截点

在 `SecurityEvaluator.evaluate()` 中，shell 族工具的统一入口处增加角色检查：

```java
public Result evaluate(String toolName, Map<String, Object> arguments, String userName,
                       String mode, WorkspaceBoundary boundary) {
    if (!cfg.getSecurity().isEnabled()) {
        return Result.allow();
    }

    Result result = switch (toolName) {
        case "execute_shell" -> {
            // 新增：角色权限检查（fail-fast，在所有模式匹配之前）
            if (!isAdmin(userName) && builtinToolService.requiresAdmin("execute_shell")) {
                yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
            }
            yield evaluateShell(arguments, mode, boundary);
        }
        case "list_processes" -> {
            if (!isAdmin(userName) && builtinToolService.requiresAdmin("list_processes")) {
                yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
            }
            yield Result.allow();
        }
        case "kill_process" -> {
            if (!isAdmin(userName) && builtinToolService.requiresAdmin("kill_process")) {
                yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
            }
            yield Result.allow();
        }
        case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
        default -> Result.allow();
    };

    if (result.decision() != Decision.ALLOW) {
        auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                result.rule() + ": " + result.reason(), userName);
    }

    return result;
}
```

`requiresAdmin()` 从内存缓存读取，直接返回 boolean，不引入新类。

`ChatServiceImpl.dispatchTools()` 中已有 BLOCK 处理（line 462-469），返回格式为：

```java
Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
    "操作被拒绝（安全规则: ROLE_CHECK）— 命令未执行（无权限）")
```

无需额外代码。

### 缓存

`require_admin` 值启动时全量加载到内存 `Map<String, Boolean>`，toggle 写 DB 后原子替换引用（`ConcurrentHashMap`）。假设仅通过 toggle API 修改 `require_admin`，不处理 DB 直改场景。

## API

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/zephyr-ui/builtin-tool/list` | 获取所有内置工具及其状态 | `SMRequiresPermissions` |
| POST | `/zephyr-ui/builtin-tool/toggle` | 更新 `require_admin` | 仅 admin |

toggle 接口双重保护：
1. `SMRequiresPermissions` 注解（Controller 层）
2. Service 层显式 `isAdmin()` 检查（纵深防御）

## 前端

- 路由：`/settings/tools`，设置抽屉中紧接 MCP 设置之后
- 页面：`ToolControlSettings.vue`，表格展示三行工具，`el-switch` 切换
- 无空状态（3 行种子数据永远存在）
- 非 admin 侧边栏隐藏设置入口（已有逻辑）

## 测试计划

### 验收标准

| # | 场景 | 预期 |
|---|------|------|
| TC1 | admin 调用 shell 族工具 | 正常执行 |
| TC2 | 非 admin 调用 shell 工具（require_admin=1） | 返回拒绝消息，不执行命令 |
| TC3 | admin toggle require_admin=0 | HTTP 200，状态更新 |
| TC4 | 非 admin 调用 toggle API | HTTP 403 或 SMRequiresPermissions 拦截 |
| TC5 | toggle 后缓存同步（require_admin=0 → 非 admin 可执行） | 非 admin 调用成功 |
| TC6 | 表为空（初始化未完成） | 所有角色放行 |
| TC7 | admin 自身不受 require_admin 限制 | admin 永远可执行 |
| TC8 | 非 shell 工具（use_skill、use_memory、search_knowledge）不受影响 | 非 admin 正常执行 |

### 验证步骤

```bash
# 准备
mvn clean compile -q
mvn spring-boot:run -Dspring-boot.run.profiles=me

# TC1: admin 可执行
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -H "Content-Type: application/json" \
  -d '{"message":"echo hello","workspaceId":"...","conversationId":"...","mode":"bypass"}'
# 预期：命令正常执行

# TC2: 非 admin 被拒绝
curl -u someuser:pass -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -H "Content-Type: application/json" \
  -d '{"message":"echo hello","workspaceId":"...","conversationId":"...","mode":"bypass"}'
# 预期：SSE 流中包含 "命令未执行（无权限）"

# TC3: admin toggle
curl -u admin:1 -H "X-SM-Test: 1" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/builtin-tool/toggle" \
  -H "Content-Type: application/json" \
  -d '{"toolName":"execute_shell","requireAdmin":0}'
# 预期：HTTP 200

# TC4: 非 admin toggle 被拒
curl -u someuser:pass -H "X-SM-Test: 1" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/builtin-tool/toggle" \
  -H "Content-Type: application/json" \
  -d '{"toolName":"execute_shell","requireAdmin":0}'
# 预期：HTTP 403

# TC8: 非 shell 工具不受影响
# 调用 use_skill 等，非 admin 正常执行
```

## 边界情况

| 场景 | 处理 |
|------|------|
| 表为空（初始化未完成） | `requiresAdmin()` 返回 `false`（全部放行），日志 warn |
| admin 自身 | `isAdmin()` 返回 true 时跳过 `requiresAdmin()` 检查 |
| 缓存竞态 | toggle 写 DB 后原子替换 `ConcurrentHashMap` 引用 |
| 非 admin 反复尝试 | system prompt 已有"被拒绝过的同类操作不要再尝试"指引 |
| DB 直改 `require_admin` | 不在支持范围内，只通过 toggle API 修改 |

## DDL + 初始化

- **建表**：三方言 Mapper XML `createBuiltinToolControls`，`InitialServiceImpl.tableCreate0()` 注册
- **种子数据**：建表回调中以 `insert ignore` 插入 3 行
- **不写 `zephyr-*.sql`**：种子数据走代码初始化，不走 SQL 文件

## 变更清单

| 层 | 文件 | 改动 |
|---|------|------|
| 实体 | `BuiltinToolControlEntity.java`（新增） | 表实体 |
| DAO | `BuiltinToolDao.java` + Mapper XML（新增） | 三方言建表 + 查询/更新 |
| Service | `BuiltinToolService.java` + `BuiltinToolServiceImpl.java`（新增） | 缓存管理、权限检查 |
| Controller | `BuiltinToolCtrl.java`（新增） | list + toggle 接口 |
| 安全拦截 | `SecurityEvaluator.java`（修改） | shell 族 3 个 case 入口加角色检查 |
| 初始化 | `InitialServiceImpl.java`（修改） | 建表注册 + 种子数据 |
| 前端 | `ToolControlSettings.vue`（新增） + 路由 + i18n | 设置页 |

## 审核记录

- **Architect**: ITERATE → 角色检查前置到 SecurityEvaluator、toggle API 加 admin 检查（已修正）
- **Critic**: ITERATE → 补充测试计划、纵深防御缺口修复（已修正）
- **终态**: APPROVE（所有 MUST 建议已纳入设计）
