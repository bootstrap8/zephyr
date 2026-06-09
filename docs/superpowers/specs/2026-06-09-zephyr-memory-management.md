# 记忆管理 设计规格

## 概述

实现记忆管理 CRUD，记忆内容以 Markdown 文件存储于 `~/.zephyr/memory/{user_name}/` 目录，格式兼容 Claude Code 的 auto-memory 系统。v1 版本仅支持手动管理，AI 自动创建记忆后续迭代。

## 数据模型

不新建数据库表，文件系统存储。

### 文件系统布局

```
~/.zephyr/memory/
├── admin/
│   ├── MEMORY.md                          # 索引文件
│   ├── user-用户偏好简洁回复风格.md          # 用户类型记忆
│   ├── project-项目使用Spring-Boot.md       # 项目类型记忆
│   └── user-禁止使用自定义颜色值.md
└── other_user/
    └── ...
```

用户之间通过 `user_name` 子目录隔离。

### 记忆文件格式（兼容 Claude Code）

```yaml
---
name: 用户偏好简洁回复风格
description: 用户偏好简洁的回复风格，不喜冗长
metadata:
  type: user
  created_at: 1717891200
  updated_at: 1717891200
---

用户希望回复简洁、直接，避免大段文字和重复解释。
在不需要详细解释时，用 2-3 句话直接回答问题。
```

**字段：**

| 字段 | 说明 |
|------|------|
| name | 记忆标题，唯一标识 |
| description | 简短描述（列表展示用，取正文前 60 字） |
| metadata.type | `user` / `project` |
| metadata.created_at | 创建时间（Unix 秒） |
| metadata.updated_at | 更新时间（Unix 秒） |
| 正文 | 完整记忆内容，支持 Markdown |

### 索引文件 MEMORY.md

一行一条，格式：

```markdown
- [用户偏好简洁回复风格](user-用户偏好简洁回复风格.md) — 用户偏好简洁的回复风格，不喜冗长
- [项目使用 Spring Boot 3.5.4](project-项目使用Spring-Boot.md) — 后端技术栈记忆
```

### 文件命名规则

`{type}-{sanitized-name}.md`，sanitized-name 为 name 字段的 URL 安全编码（中文保留原样）。

## API 设计

Base path: `/zephyr-ui/memory`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 获取记忆列表（支持 `?type=user` 筛选） |
| GET | `/detail` | 获取单条记忆完整内容（`?name=xxx`） |
| POST | `/create` | 新增记忆 |
| POST | `/update` | 修改记忆 |
| POST | `/delete` | 删除记忆（支持单个和批量） |

### 接口详情

**`GET /list?type=user`** — 扫描 `~/.zephyr/memory/{user_name}/` 目录，解析每个 `.md` 文件的 YAML frontmatter，返回列表。`type` 参数为空时返回全部。

```json
{
  "body": [
    {
      "name": "用户偏好简洁回复风格",
      "type": "user",
      "description": "用户偏好简洁的回复风格...",
      "createdAt": 1717891200,
      "updatedAt": 1717891200
    }
  ]
}
```

**`GET /detail?name=xxx`** — 读取单个文件，返回 frontmatter + markdown 正文（存入 `content` 字段）。

**`POST /create`** — body `{ name, type, content }`。生成 `.md` 文件（自动填 `created_at`/`updated_at`），并追加 `MEMORY.md` 索引行。

**`POST /update`** — body `{ name, type, content }`。覆盖写 `.md` 文件（更新 `updated_at`），并更新 `MEMORY.md` 中对应行。如果 name 变更，同时重命名文件和更新索引中的链接。

**`POST /delete`** — body `{ names: ["记忆1", "记忆2"] }`。删除对应 `.md` 文件，从 `MEMORY.md` 移除对应行。支持批量。

### 安全

- 所有操作通过 `UserContext.get().getUserName()` 确定用户目录，禁止越权
- 文件写入前校验 name 不为空、type 为 `user` 或 `project`

## 包结构

```
com.github.hbq969.ai.zephyr.memory/
├── ctrl/MemoryCtrl.java
├── service/MemoryService.java
├── service/impl/MemoryServiceImpl.java
└── model/MemoryVO.java
```

不需要 DAO/Mapper/Entity，不建数据库表。整个模块 4 个 Java 文件。

### MemoryServiceImpl 核心逻辑

- `list(type)` → `Files.list()` 遍历目录，SnakeYAML 解析 frontmatter，按 type 过滤
- `detail(name)` → 读取单个文件，分离 frontmatter 和 body
- `create(name, type, content)` → 构建 frontmatter YAML + body，写 `.md`，更新索引
- `update(name, type, content)` → 覆盖写 `.md`（更新 updated_at），更新索引
- `delete(names[])` → 删除文件 + 更新索引

### MemoryVO

```java
public class MemoryVO {
    private String name;
    private String type;
    private String description;
    private String content;   // 仅 detail 接口返回
    private long createdAt;
    private long updatedAt;
}
```

## 前端设计

### MemorySettings.vue 页面布局

```
┌─────────────┬─────────────────────────────────────────┐
│ ← 记忆管理   │                                         │
│             │  ☐  📄 记忆标题                  [✎] [🗑] │
│ [全部 3]    │  描述预览文字...                          │
│ [用户 2]    │  user · 2026-06-08                      │
│ [项目 1]    │                                         │
│             │  ── 展开内容（Markdown 渲染）──           │
│ [+ 新增记忆] │                                         │
│             │  ☐  📄 另一条记忆                  [✎] [🗑] │
│             │  ...                                    │
└─────────────┴─────────────────────────────────────────┘
```

### 交互

1. **类型筛选** — 顶部 filter-tab（全部/用户/项目），切换过滤列表
2. **卡片列表** — 默认显示描述预览（正文前 60 字），点击标题展开/收起查看完整 Markdown 渲染内容
3. **新增** — 弹窗表单：名称（input）、类型（select: 用户/项目）、内容（textarea，支持 Markdown）
4. **编辑** — 弹窗预填已有内容，保存后覆盖写入
5. **删除** — 单条删除确认弹窗 + 批量勾选后批量删除确认
6. **空状态** — 无记忆时显示引导

### 新建/编辑弹窗

```
┌─────────────────────────────┐
│ 新增记忆                  ✕ │
├─────────────────────────────┤
│  名称 *                     │
│  ┌───────────────────────┐ │
│  │                       │ │
│  └───────────────────────┘ │
│  类型 *                     │
│  ┌───────────────────────┐ │
│  │ 用户              ▾   │ │
│  └───────────────────────┘ │
│  内容 *                     │
│  ┌───────────────────────┐ │
│  │                       │ │
│  │                       │ │
│  └───────────────────────┘ │
├─────────────────────────────┤
│              [取消]  [保存] │
└─────────────────────────────┘
```

### 前端文件变更

| 文件 | 变更 |
|------|------|
| `MemorySettings.vue` | 重写，对接 API |
| `store/settings.ts` | 新增 `loadMemories`/`createMemory`/`updateMemory`/`deleteMemories` |
| `types/chat.ts` | 补充 `MemoryItem` 类型定义 |

### 样式

遵循项目 DESIGN.md warm canvas 设计系统：canvas 背景（`#faf9f5`）、coral 主色（`#cc785c`）、Georgia 衬线标题、StyreneB/Inter 正文。filter-tab 用 `category-tab` 规范。

空状态：

```
┌──────────────────────────────────────────┐
│                                          │
│                   📄                      │
│              暂无记忆                      │
│      点击「+ 新增记忆」创建第一条           │
│                                          │
└──────────────────────────────────────────┘
```

## 开发规范（必须遵守）

- Controller 用 `@RequestMapping`，禁止 `@GetMapping/@PostMapping`
- 必须 `@Tag(name)`、`@Operation(summary)`、`@ResponseBody`
- 前端 URL 不含 `outDir` 前缀（axios baseURL 已覆盖）
- 不新建数据库表，不需要 DAO/Mapper/InitialServiceImpl
- YAML 解析用 SnakeYAML（Spring Boot 内置依赖）
- 用户隔离通过 `~/.zephyr/memory/{user_name}/` 子目录

## 安全要求

- 删除/修改操作校验 user_name，禁止越权（检查文件所在目录属于当前用户）
- name 和 type 必填，type 仅允许 `user` 或 `project`
- 文件操作限制在 `~/.zephyr/memory/{user_name}/` 目录内，禁止路径穿越

## 待办

| # | 模块 | 状态 |
|---|------|------|
| 1 | 记忆管理（手动 CRUD） | 待实现 |
| 2 | AI 自动记忆 | 后续迭代 |
| 3 | feedback / reference 类型支持 | 后续迭代 |
| 4 | 记忆搜索 | 后续迭代 |
