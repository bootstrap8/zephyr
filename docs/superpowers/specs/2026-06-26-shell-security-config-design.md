# Shell 安全配置管理 — 设计文档

> 经 ralplan 共识审核 + 用户简化决策。

## 问题定义

当前 `zephyr.shell.allowed-commands`、`zephyr.security.default-allow-commands`、`zephyr.security.hard-block.shell-patterns`、`zephyr.security.soft-block.shell-patterns` 四个安全配置项都写在 `application.yml` 中，通过 `@PostConstruct` 一次性加载到内存。修改配置需要编辑 YAML 文件并重启服务，用户无法在界面上管理。

**目标：** 四个配置项迁移到数据库表，提供管理页面，修改后即时刷新内存缓存（无需重启）。

## 关键设计决策

### 存储：纯 DB，YAML 全部移除

- `application.yml` 中 4 个配置项**完全删除**
- `ZephyrConfigProperties` 中对应字段**删除**（不是标记 @Deprecated，是完全移除）
- DB 是唯一数据源，初始数据由 SQL 初始化脚本（`zephyr-*.sql`）提供
- 新建环境首次启动时 SQL 脚本自动播种，DB 有数据即可用

### 架构：SecurityConfigService 统一管理

```
┌──────────────────────────┐
│  SecurityConfigCtrl      │  @SMRequiresPermissions 每个端点
│  /security/{type}/list   │
│  /security/{type}/add    │  type 参数用枚举约束
│  /security/{type}/delete │
│  /security/{type}/update │
│  /security/{type}/toggle │
└───────────┬──────────────┘
            │ CRUD → refresh() + auditLogger
┌───────────▼──────────────┐
│  SecurityConfigService    │
│  volatile ConfigSnapshot  │
│  @PostConstruct: loadDb() │  直接从 DB 加载，无 YAML fallback
│  post-CRUD: refresh()     │
└───────────┬──────────────┘
            │ DAO
┌───────────▼──────────────┐
│  1 张表                    │
│  zephyr_security_rules    │
└──────────────────────────┘

消费者:
  ChatServiceImpl ──→ snapshot.shellAllowedCommands()
  SecurityEvaluator ──→ snapshot.hardBlockPatterns() 等
  (均在方法入口一次性获取 snapshot 引用)
```

### 前端：单页面 4 Tab

路径 `/settings/security`，一个页面内用 Tab 切换四个配置区域。

### 数据模型

```sql
CREATE TABLE IF NOT EXISTS zephyr_security_rules (
    id          VARCHAR(64)  PRIMARY KEY,
    rule_type   VARCHAR(32)  NOT NULL,
    rule_value  VARCHAR(512) NOT NULL,
    description VARCHAR(256),
    enabled     SMALLINT DEFAULT 1,
    created_at  BIGINT,
    updated_at  BIGINT,
    UNIQUE(rule_type, rule_value)
);
```

`rule_type`: `SHELL_ALLOWED` | `DEFAULT_ALLOW` | `HARD_BLOCK` | `SOFT_BLOCK`

### API 设计

```
GET    /zephyr-ui/security/{type}/list       @SMRequiresPermissions(apiKey="security_list")
POST   /zephyr-ui/security/{type}/add        @SMRequiresPermissions(apiKey="security_add")
POST   /zephyr-ui/security/{type}/delete     @SMRequiresPermissions(apiKey="security_delete")
POST   /zephyr-ui/security/{type}/update     @SMRequiresPermissions(apiKey="security_update")
POST   /zephyr-ui/security/{type}/toggle     @SMRequiresPermissions(apiKey="security_update")
```

输入校验：
- `HARD_BLOCK`/`SOFT_BLOCK`：`Pattern.compile()` 前置校验
- `SHELL_ALLOWED`/`DEFAULT_ALLOW`：`^[a-zA-Z0-9._-]+$`
- `description`：max 256 字符
- `@ExceptionHandler(IllegalArgumentException.class)` → HTTP 400

### 改造影响

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | **删除** `Shell.allowedCommands`、`Security.defaultAllowCommands`、`HardBlock.shellPatterns`、`SoftBlock.shellPatterns` 四个字段。Shell/Security 其他字段保留 |
| `application.yml` | **删除**对应 4 个 YAML key 及其值 |
| `SecurityEvaluator.java` | 删除 `@PostConstruct init()` 等，改为注入 SecurityConfigService |
| `ChatServiceImpl.java` | 删除 `initShellWhitelist()` 和 `shellWhitelist` 字段，注入 SecurityConfigService |
| 新增 | SecurityRuleEntity, SecurityConfigDao, SecurityConfigService, SecurityConfigCtrl, Mapper XML × 4 |
| SQL | `zephyr-*.sql` 添加 INSERT 初始数据 |
| 前端 | SecuritySettings.vue, 路由, store |

### SQL 初始化脚本

将当前 `application.yml` 中的默认值转为 INSERT 语句写入 `zephyr-*.sql`。使用 `INSERT INTO ... SELECT ... WHERE NOT EXISTS` 保证幂等。

### 安全考虑

- ConfigSnapshot 中所有 Pattern 在 `@PostConstruct` 加载时预编译
- 非法正则（DB 脏数据）在加载时 log.warn 跳过
- 所有 CRUD 端点有 `@SMRequiresPermissions` 权限控制
- 所有变更写入 AuditLogger
- 消费者在方法入口一次性获取 snapshot 引用
