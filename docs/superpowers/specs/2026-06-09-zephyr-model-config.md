# 模型配置管理 设计规格

## 概述

实现模型配置 CRUD，按用户隔离，API Key AES-256-GCM 加密存储。

## 数据模型

### model_configs 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID 简写 |
| user_name | varchar(64) | 用户名 |
| name | varchar(128) | 模型名称 |
| base_url | varchar(512) | API Base URL |
| api_key_encrypted | text | 加密存储的 API Key |
| is_default | smallint | 默认模型标记（0/1） |
| created_at | bigint | Unix 秒 |
| updated_at | bigint | Unix 秒 |

> **约定：** 所有用户关联配置统一用 `user_name` 字段，后续 SSO 集成也只能取到用户名。

## API 设计

Base path: `/zephyr-ui/model-config`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 当前用户模型列表 |
| POST | `/create` | 新增模型（name/baseUrl/apiKey） |
| POST | `/update` | 修改模型（id/name/baseUrl/apiKey） |
| POST | `/delete` | 删除模型（id） |
| POST | `/set-default` | 设为默认（id） |

## 包结构

```
com.github.hbq969.ai.zephyr.config/
├── ctrl/ModelConfigCtrl.java
├── service/ModelConfigService.java
├── service/impl/ModelConfigServiceImpl.java
├── dao/ModelConfigDao.java
├── dao/entity/ModelConfigEntity.java
└── dao/mapper/
    ├── common/ModelConfigMapper.xml
    ├── embedded/ModelConfigMapper.xml
    ├── mysql/ModelConfigMapper.xml
    └── postgresql/ModelConfigMapper.xml
```

## 开发规范（必须遵守）

- Controller 用 `@RequestMapping`，禁止 `@GetMapping/@PostMapping`
- 必须 `@Tag(name)`、`@Operation(summary)`、`@ResponseBody`
- 前端 URL 不含 `outDir` 前缀（axios baseURL 已覆盖）
- 新建表：三方言（embedded/postgresql/mysql）Mapper XML DDL + `InitialServiceImpl.tableCreate0()` 注册
- DDL 必须 `if not exists`
- API Key 加密：用项目中已有的 `encryptConfig` 进行 AES 加密

## 安全要求

- API Key 存储前 AES 加密，返回时脱敏（`sk-****xxxx`）
- 删除/修改操作校验 user_name，禁止越权访问
- 每个用户只能有一个默认模型（设置新默认时取消旧默认）

## 前端设计

### 1. 设置入口（左下角 `...` → 设置面板）

当前 ChatSidebar 底部用户行点击后 emit `openSettings`，需在 ChatView 中响应，弹出一个**设置浮层面板**：

```
┌─────────────────────┐
│ 设置              ✕ │
├─────────────────────┤
│ 当前模型  DeepSeek-V3 →│  ← 点击跳转 /settings/model
│ MCP 管理      3 个  →│  ← 点击跳转 /settings/mcp
│ Skill 管理    5 个  →│  ← 点击跳转 /settings/skills
│ 记忆管理            →│  ← 点击跳转 /settings/memory
│ 暗黑模式      [开关] │
│ 上下文压缩    [开关] │
│ 文件记忆      [开关] │
├─────────────────────┤
│            [关闭]    │
└─────────────────────┘
```

**实现：** ChatView.vue 中新增 `SettingsPanel.vue` 组件（浮层 modal），或直接用 Element Plus `el-dialog`。

### 2. 状态栏模型显示

StatusBar.vue 中已实现，但需改为从后端获取：
- 默认模型名从 `model-config/list` API 获取，取 `is_default=true` 的
- 点击状态栏模型弹出 popover 时，列表数据从 API 获取而非 store 硬编码
- 选择模型后调用 `/set-default` 接口

### 3. 输入框模型切换

在 InputArea.vue 的左侧工具栏增加一个模型选择器：

```
[▾ DeepSeek-V3] ┌──────────────────────────┐  [📎] [↑]
                 │ 给 zephyr 发送消息...     │
                 └──────────────────────────┘
```

- 左侧显示当前模型名，可下拉切换
- 下拉列表数据从 `/model-config/list` 获取
- 切换后更新 Pinia store + 后端默认模型

### 4. 前端文件变更清单

| 文件 | 变更 |
|------|------|
| `ChatView.vue` | 新增设置面板弹窗，响应 ChatSidebar 的 openSettings 事件 |
| `ChatSidebar.vue` | 用户行点击弹出设置面板 |
| `StatusBar.vue` | 模型数据改为从 API 获取 |
| `InputArea.vue` | 左侧增加模型切换下拉选择器 |
| `ModelSettings.vue` | 已有 UI，对接真实 API |
| `store/settings.ts` | 从 API 加载模型列表 |

### 5. API 对接

- `axios({ url: '/model-config/list', method: 'get' })` 获取列表
- 新增/编辑/删除/设默认均 POST
- API Key 显示脱敏格式（`sk-****xxxx`），编辑时才可重新输入

## 待办清单

| # | 模块 | 状态 |
|---|------|------|
| 1 | 模型配置（数据库 + API + 前端对接） | ✅ 完成 |
| 2 | MCP 管理 | ✅ 完成 |
| 3 | Skill 管理 | ✅ 完成 |
| 4 | 用户偏好设置 | 待办 |
| 5 | 记忆管理 | 待办 |
| 6 | LLM 对话接入 | 待办 |
| 7 | 会话/消息持久化 | 待办 |
