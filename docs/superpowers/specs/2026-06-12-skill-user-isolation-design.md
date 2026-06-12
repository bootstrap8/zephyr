# Skill 用户隔离 + 共享机制设计

## 背景

Skill 管理目前存在用户隔离缺陷：数据库层面已通过 `user_name` 字段隔离，但文件存储所有用户共享同一目录 `~/.zephyr/skills/`。多用户安装同名 skill 会互相覆盖。

## 目标

1. 文件存储按用户隔离
2. 支持共享 skill（admin 管理，全员使用）
3. 同名 skill 优先级：用户私有 > 共享
4. 旧文件自动迁移

## 目录结构

```
~/.zephyr/skills/
├── share/             # 共享 skill（全员可读，仅 admin 可管理）
│   ├── skill-a/
│   │   └── SKILL.md
│   └── skill-b/
│       └── SKILL.md
├── admin/             # admin 私有 skill
│   └── ...
└── {userName}/        # 普通用户私有 skill
    └── ...
```

## 优先级规则

同名 skill：**用户目录 > 共享目录**

解析 skill 时：先查 `{userName}/{skill}/SKILL.md`，找不到 fallback 到 `share/{skill}/SKILL.md`。

## 数据库变更

`skill_config` 表新增 `scope` 字段：

```sql
ALTER TABLE skill_config ADD COLUMN scope VARCHAR(16) DEFAULT 'user';
```

| scope    | 含义         | 管理权限      | 使用权限    |
|----------|-------------|--------------|-----------|
| `user`   | 用户私有      | 本人          | 本人       |
| `shared` | 全平台共享    | 仅 admin     | 所有用户   |

## API 变更

### GET /list

返回当前用户的私有 skill + 所有共享 skill，每条带 `scope` 字段。同名去重：私有覆盖共享。

### POST /install

body 新增可选 `scope` 字段：
- `"user"`（默认）：安装到 `{userName}/` 目录
- `"shared"`：安装到 `share/` 目录，仅 admin 可操作，否则返回 403

### POST /uninstall

校验权限：`shared` 记录仅 admin 可卸载，`user` 记录仅本人可卸载。

### POST /toggle

相同权限校验。

### GET /sync-scan

扫描结果分类：先用 `scope` 区分（已有的 shared/user），未入库的按平台路径归属。

### POST /sync-install

body 新增可选 `scope` 字段，逻辑同 install。

## 运行时查找（ChatServiceImpl.executeUseSkill）

```
1. ~/.zephyr/skills/{userName}/{skill}/SKILL.md
2. ~/.zephyr/skills/share/{skill}/SKILL.md
3. 都找不到 → 错误
```

## ContextBuilder.buildSkillIndex

同名去重：`{userName}/` 目录下的 skill 覆盖 `share/` 下同名 skill（用户已安装同名私有版本时，不展示共享版）。

## 旧文件迁移

`SkillServiceImpl` 添加 `@PostConstruct` 迁移方法：

1. 检查 `~/.zephyr/skills/` 下是否有 `.migrated-to-isolation` 标记文件，有则跳过
2. 扫描 skills 根目录，收集直接包含 `SKILL.md` 的子目录（旧结构特征）
3. 移动到 `share/` 目录，冲突时跳过
4. 更新数据库中对应记录的 `install_path` 为新的 share 路径，`scope` 设为 `shared`
5. 写入 `.migrated-to-isolation` 标记文件

## 实现清单

| 文件 | 改动 |
|------|------|
| `SkillConfigEntity.java` | 新增 `scope` 字段 |
| `skill_config` Mapper XML（三方言 + common DML） | 新增 `scope` 列 |
| `skill-migration.sql`（三语言） | `ALTER TABLE ADD COLUMN scope` |
| `SkillServiceImpl.java` | 新增 `skillDir()`、迁移方法；修改路径构建、权限校验 |
| `ChatServiceImpl.java` | `executeUseSkill()` 传 `userName`，两阶段查找 |
| `ContextBuilder.java` | `buildSkillIndex()` 同名去重 |
| `SkillVO.java` | 新增 `scope` 字段 |
| `SkillCtrl.java` | `list` 返回加 `scope` |
| 前端 skill 管理页面 | 分类展示（共享/我的），共享区非 admin 只读 |
