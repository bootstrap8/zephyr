# Skill Pack（组合技能包）安装设计

## 问题

当前 `findSkillRoot` 只取第一个含 `SKILL.md` 的目录，导致 git 仓库、压缩包等包含多个 skill 的"组合包"只能安装第一个 skill，其余被忽略。

## 目标

支持一次性安装组合包内所有 skill，按 `pack:skill` 格式命名，保持目录层级存储。

## 检测规则

解压/克隆后递归查找所有 `SKILL.md` 文件，按所在层级判定：

| 场景 | 示例结构 | 判定 |
|------|----------|------|
| 单 skill | `my-skill/SKILL.md`（仅 1 个顶层子目录 + 1 个 SKILL.md）| 单 skill，无 pack |
| 多 skill 平铺 | `pack/技能A/SKILL.md` + `pack/技能B/SKILL.md` | pack=`pack` |
| 中间 skills/ 层 | `pack/skills/技能A/SKILL.md` + `pack/skills/技能B/SKILL.md` | pack=`pack`，跳过 `skills/` |
| 目录本身是 skill | `pack/SKILL.md`（无子目录 SKILL.md） | 单 skill，无 pack |
| 顶层 + 子目录混合 | `pack/SKILL.md` + `pack/技能A/SKILL.md` | pack，忽略顶层 SKILL.md，取其 name 作 pack 名 |

**判定为 pack 的条件：** `findSkillRoots()` 返回 ≥2 个 skill 目录。

**pack 名：** 取顶层目录名。如果顶层自身也有 `SKILL.md`，取其 `name` frontmatter 字段作为 pack 名（顶层的 SKILL.md 不作为 skill 安装，仅提取包名）。

## 命名规则

- 有 pack：`skillName = "pack:skill"`，如 `superpowers:brainstorming`
- 无 pack：`skillName = skill`，如 `brainstorming`

## 存储结构

```
~/.zephyr/skills/
  brainstorming/                   ← 无 pack 的单个 skill
    SKILL.md
  superpowers/                     ← pack 目录
    brainstorming/                 ← pack 内 skill
      SKILL.md
    systematic-debugging/
      SKILL.md
```

## DB 字段

`SkillConfigEntity` 无需改动。`skillName` 存完整标识 `superpowers:brainstorming`，`installPath` 存实际路径 `~/.zephyr/skills/superpowers/brainstorming`。

## 展示

- Skill 列表：显示 `skillName`
- 聊天能力 > Skill 菜单：按 `skillName` 字母排序
- 搜索过滤：`skillName` 和 `displayName` 都参与匹配

## 卸载

- 卸载单个 skill：删除目录 + DB 记录
- 卸载 pack 内最后一个 skill 后：清理空的 pack 目录（如果存在）
- 卸载全部使用同一个"卸载"接口，无变化

## 重复检测

安装时对每个待安装的 skill 逐一检查 `skillName` 是否已存在。已存在的跳过（或报错），不重复安装。

## 安装流程变化

```
install(upload/git/url/local)
  → 下载/解压/克隆到 tmpDir
  → findSkillRoots(tmpDir) → List<Path> 
  → 对每个 skillRoot:
      - detectSkillName(skillRoot) → skill 名
      - 计算 skillName = pack 存在 ? "pack:skill" : skill
      - 查重
      - 复制到 ~/.zephyr/skills/{packName}/{skill}/ 或 ~/.zephyr/skills/{skill}/
      - insertSkillConfig
  → 返回 List<SkillVO>
```

## 接口变化

- `POST /skill/install` — 返回 `List<SkillVO>`（原是单个 `SkillVO`）
- `POST /skill/upload` — 返回 `List<SkillVO>`
- 列表、卸载、启停接口不变

## 前端适配

- install/upload 返回数组后，`loadSkills()` 刷新列表
- 安装成功时提示安装了 N 个 skill（如 "成功安装 3 个 Skill"）
