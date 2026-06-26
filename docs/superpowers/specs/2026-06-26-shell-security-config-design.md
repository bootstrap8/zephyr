# Shell 安全配置管理 — 设计文档

> 经 ralplan 共识审核 (Planner → Architect → Critic)，已整合审核反馈。

## 问题定义

当前 `zephyr.shell.allowed-commands`、`zephyr.security.default-allow-commands`、`zephyr.security.hard-block.shell-patterns`、`zephyr.security.soft-block.shell-patterns` 四个安全配置项都写在 `application.yml` 中，通过 `@PostConstruct` 一次性加载到内存。修改配置需要编辑 YAML 文件并重启服务，用户无法在界面上管理。

**目标：** 四个配置项迁移到数据库表，提供管理页面，修改后即时刷新内存缓存（无需重启）。

## RALPLAN-DR 审核摘要

### Principles

- P1: 安全配置的修改不得存在竞态窗口，避免部分加载状态导致误放行
- P2: 运行时安全评估必须是自包含且可验证的 — 拒绝决策不能依赖可能失效的外部状态
- P3: 配置变更审计追踪 — 谁在何时修改了哪条安全规则必须可追溯
- P4: 脏数据韧性 — 数据库中的非法正则或恶意数据不能导致安全评估静默失效
- P5: 最小权限 — 安全配置管理页面必须需要管理员身份认证

### 关键识别的风险及修正

| # | 风险 | 严重程度 | 修正方案 |
|---|------|---------|---------|
| R1 | 启动时 DB 为空 → 安全规则全部失效（fail-open） | **高危** | 两阶段初始化：YAML 种子 → DB 合并 |
| R2 | 管理 API 无权限控制 | **高危** | 所有端点加 `@SMRequiresPermissions` |
| R3 | 4 张独立表 → 代码重复 75%，扩展性差 | **中危** | 1 张统一表 + type 鉴别器 |
| R4 | 正则非法 → 静默跳过，规则不生效 | **中危** | 服务端 Pattern.compile() 前置校验 |
| R5 | 无配置变更审计日志 | **中危** | CRUD 操作写入 AuditLogger |
| R6 | application.yml 移除后已有部署丢失自定义规则 | **中危** | 迁移工具 + YAML 保留作为种子 |

## 关键设计决策

### 存储：DB 为主，YAML 为冷启动种子

YAML 中**保留**四个配置项，作为部署时的"已知安全基线"。运行时 DB 优先：

- **阶段 1（同步，@PostConstruct）**：从 YAML 读取配置，构建初始 ConfigSnapshot，所有消费者立即可用（安全启动）
- **阶段 2（异步，ApplicationReadyEvent）**：从 DB 读取，与 YAML 种子合并（DB 覆盖 YAML），替换 snapshot
- **DB 为空时**：将 YAML 种子写入 DB（自动播种），后续变更通过 UI 操作 DB
- **运行时**：所有增删改通过 DB，操作后 refresh() 替换快照

### 架构：SecurityConfigService 统一管理

```
┌──────────────────────────┐
│  SecurityConfigCtrl      │  @SMRequiresPermissions 每个端点
│  /security/{type}/list   │
│  /security/{type}/add    │  type 参数用枚举约束:
│  /security/{type}/delete │  shell-allowed | default-allow
│  /security/{type}/update │  | hard-block | soft-block
└───────────┬──────────────┘
            │ CRUD → refresh() + auditLogger
┌───────────▼──────────────────────────────────┐
│  SecurityConfigService                        │
│  volatile ConfigSnapshot snapshot;            │
│  CountDownLatch latch = new CountDownLatch(1);│
│  Phase 1: loadFromYaml() → build snapshot     │
│           → latch.countDown()                 │
│  Phase 2: loadFromDb() → merge → refresh()    │
│  消费者 await(latch) 后才读取 snapshot         │
└───────────┬──────────────────────────────────┘
            │ DAO (单表操作)
┌───────────▼──────────────────────────────────┐
│  1 张表: zephyr_security_rules                │
│  id | rule_type | rule_value | description    │
│  | enabled | created_at | updated_at          │
│  UNIQUE(rule_type, rule_value)                │
└──────────────────────────────────────────────┘

消费者:
  ChatServiceImpl ──→ securityConfigService.getSnapshot()
  SecurityEvaluator ──→ securityConfigService.getSnapshot()
  (均在方法入口一次性获取 snapshot 引用，避免混合状态)
```

### 前端：单页面 4 Tab

路径 `/settings/security`，一个页面内用 Tab 切换四个配置区域。命令类 Tab 数据结构相同可复用组件。

### 数据模型

使用 **1 张统一规则表** + type 鉴别器：

```sql
CREATE TABLE IF NOT EXISTS zephyr_security_rules (
    id          VARCHAR(32)  PRIMARY KEY,
    rule_type   VARCHAR(32)  NOT NULL,
    rule_value  VARCHAR(512) NOT NULL,
    description VARCHAR(256),
    enabled     SMALLINT DEFAULT 1,
    created_at  BIGINT,
    updated_at  BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_zsr_type_value
    ON zephyr_security_rules (rule_type, rule_value);
```

`rule_type` 枚举：`SHELL_ALLOWED` | `DEFAULT_ALLOW` | `HARD_BLOCK` | `SOFT_BLOCK`

> **选择 1 表而非 4 表的理由**：四类规则结构相同，统一表减少 75% 代码重复，新增规则类型只需加枚举值。`UNIQUE(rule_type, rule_value)` 保证同类规则不重复。

### API 设计

每个端点均需 `@SMRequiresPermissions` 权限控制，参考 `McpCtrl` 模式：

```
GET    /zephyr-ui/security/{type}/list    @SMRequiresPermissions(menu="zephyr_api", menuDesc="zephyr智能体", apiKey="security_list", apiDesc="安全配置_列表")
POST   /zephyr-ui/security/{type}/add     @SMRequiresPermissions(... apiKey="security_add", apiDesc="安全配置_新增")
POST   /zephyr-ui/security/{type}/delete  @SMRequiresPermissions(... apiKey="security_delete", apiDesc="安全配置_删除")
POST   /zephyr-ui/security/{type}/update  @SMRequiresPermissions(... apiKey="security_update", apiDesc="安全配置_修改")
```

`{type}` 在 Controller 中用 Java enum 约束，非法值 Spring 自动返回 400。

**输入校验（服务端强制）：**
- `HARD_BLOCK` / `SOFT_BLOCK` 类型：`Pattern.compile(value)` 前置校验，非法正则返回错误
- `SHELL_ALLOWED` / `DEFAULT_ALLOW` 类型：命令名匹配 `^[a-zA-Z0-9._-]+$`，拒绝含空格/特殊字符的输入
- `description` 长度限制 256 字符

### 改造影响

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | 4 个字段保留作为冷启动种子，加 `@Deprecated` 注释说明运行时以 DB 为准 |
| `application.yml` | 4 个 key 保留，作为部署时的安全基线 |
| `SecurityEvaluator.java` | 删除 `@PostConstruct init()`/`initReadOnlyCommands()`/`initPatterns()`，改为依赖 SecurityConfigService；evaluate() 方法入口一次性获取 snapshot 引用 |
| `ChatServiceImpl.java` | 删除 `initShellWhitelist()` 和 `shellWhitelist` 字段，改为从 SecurityConfigService 获取 |
| 新增 | SecurityConfigCtrl, SecurityConfigService, SecurityConfigDao, SecurityRuleEntity, Mapper XML × 1 |
| 前端新增 | SecuritySettings.vue, 路由注册, settings store 扩展 |

### 前端 tab 结构

| Tab 名称 | rule_type | UI 元素 |
|----------|-----------|---------|
| 命令白名单 | SHELL_ALLOWED | 命令名 + 描述 + 操作(增/删) |
| 默认允许命令 | DEFAULT_ALLOW | 同上 |
| 硬阻断规则 | HARD_BLOCK | 正则模式 + 描述 + 操作(增/删/改) |
| 软阻断规则 | SOFT_BLOCK | 同上 |

### 迁移工具

对于已有自定义 YAML 配置的部署，提供 `MigrateYamlToDb` 迁移工具：

- 读取 `application.yml` 中的 4 个 key
- 逐条写入 `zephyr_security_rules` 表（幂等，使用 UNIQUE 约束防重复）
- 写入后 YAML 中的 key 可保留作为种子，不再主动加载
- 提供 `--dry-run` 选项预览将要写入的规则

### 安全考虑

- **启动屏障**：CountDownLatch 确保 SecurityEvaluator/ChatServiceImpl 不会在 ConfigSnapshot 为空时处理请求
- **Fail-closed**：如果 DB 加载失败，保留 YAML 种子快照（不是空列表），系统在已知安全基线下运行
- **ReDoS 防护**：正则输入加前置校验，文档注释说明复杂度风险（当前规则数量 < 100 时影响可忽略）
- **审计日志**：所有增删改操作写入 AuditLogger，记录操作人、操作类型、规则内容
- **并发安全**：volatile 快照 + synchronized 写 + 消费者方法入口一次性引用，避免混合状态读取

### HardBlock.pathPrefixes 范围说明

当前 `HardBlock.pathPrefixes`（文件写入 HARD BLOCK 路径前缀列表）**暂不纳入本次迁移范围**。原因：pathPrefixes 用于文件写入类工具（write_file/edit_file），与本次迁移的 shell 命令安全规则属于不同评估路径。该字段可后续作为独立任务迁移到同一张 `zephyr_security_rules` 表（rule_type=HARD_BLOCK_PATH），本次先保持 YAML 配置。

### SQL 初始化脚本

将当前 `application.yml` 中的默认值写入 `zephyr-zh-CN.sql`（仅在首次建表后播种，DB 优先模式下随后会被覆盖）。使用 `INSERT INTO ... WHERE NOT EXISTS` 保证幂等。三语言 SQL 内容相同（描述暂用中文），后续可通过前端 i18n 处理。
