# Shell 安全配置管理 — 设计文档

## 问题定义

当前 `zephyr.shell.allowed-commands`、`zephyr.security.default-allow-commands`、`zephyr.security.hard-block.shell-patterns`、`zephyr.security.soft-block.shell-patterns` 四个安全配置项都写在 `application.yml` 中，通过 `@PostConstruct` 一次性加载到内存。修改配置需要编辑 YAML 文件并重启服务，用户无法在界面上管理。

**目标：** 四个配置项迁移到数据库表，提供管理页面，修改后即时刷新内存缓存（无需重启）。

## 关键设计决策

### 存储：纯 DB，YAML 不保留默认值

用户选择方案 A — YAML 中完全移除这些配置字段，启动时从 DB 加载。首次启动时 DB 无数据则使用空默认值（空列表）。管理员通过管理页面配置初始值。

### 架构：新 SecurityConfigService 统一管理内存缓存

不在 ChatServiceImpl / SecurityEvaluator 中各自维护 `@PostConstruct`，而是新建 `SecurityConfigService`：

- 启动时从 DB 加载全部配置到内存（线程安全的 volatile 字段）
- ChatServiceImpl、SecurityEvaluator 改为从 SecurityConfigService 读取
- 每增/删/改操作后自动调用 `refresh()` 重新从 DB 加载

```text
┌──────────────────────┐
│ SecurityConfigCtrl   │  REST API
└────────┬─────────────┘
         │ CRUD
┌────────▼─────────────┐
│ SecurityConfigService │  内存缓存 + 业务逻辑
└────────┬─────────────┘
         │ DAO
┌────────▼─────────────┐
│ 4 张 zephyr_* 表     │  DB 存储
└──────────────────────┘

消费者:
  ChatServiceImpl──→ SecurityConfigService.getShellAllowedCommands()
  SecurityEvaluator─→ SecurityConfigService (hard/soft patterns, default allow)
```

### 前端：单页面 4 Tab

路径 `/settings/security`，一个页面内用 Tab 切换四个配置区域。命令类 Tab 数据结构相同可复用组件。

### 数据模型

| 表名 | 字段 | 用途 |
|------|------|------|
| `zephyr_shell_allowed_cmds` | id, command_name, description, created_at, updated_at | whitelist 模式允许的命令 |
| `zephyr_security_default_allow_cmds` | id, command_name, description, created_at, updated_at | default 模式免确认命令 |
| `zephyr_security_hard_block_rules` | id, pattern, description, created_at, updated_at | 硬阻断正则 |
| `zephyr_security_soft_block_rules` | id, pattern, description, created_at, updated_at | 软阻断正则 |

### API 设计

```
GET    /zephyr-ui/security/{type}/list
POST   /zephyr-ui/security/{type}/add
POST   /zephyr-ui/security/{type}/delete
POST   /zephyr-ui/security/{type}/update    (仅规则表使用)
```

type: `shell-allowed` | `default-allow` | `hard-block` | `soft-block`

### 改造影响

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | 删除 shell.allowedCommands、security.defaultAllowCommands、hardBlock.shellPatterns、softBlock.shellPatterns 字段 |
| `application.yml` | 删除对应 YAML key |
| `SecurityEvaluator.java` | 删除 init()/initReadOnlyCommands()/initPatterns()，改为注入 SecurityConfigService |
| `ChatServiceImpl.java` | 删除 initShellWhitelist()，改为注入 SecurityConfigService |
| 新增 | SecurityConfigCtrl, SecurityConfigService, SecurityConfigDao, 4 Entity, Mapper XML × 4 |
| 前端新增 | SecuritySettings.vue, 路由注册, settings store 扩展 |

### 前端 tab 结构

| Tab 名称 | 数据 | UI 元素 |
|----------|------|---------|
| 命令白名单 | shell_allowed_cmds | 命令名 + 描述 + 操作(增/删) |
| 默认允许命令 | security_default_allow_cmds | 同上 |
| 硬阻断规则 | security_hard_block_rules | 正则模式 + 描述 + 操作(增/删/改) |
| 软阻断规则 | security_soft_block_rules | 同上 |
