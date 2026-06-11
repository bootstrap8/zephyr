# 工作空间（Workspace）设计

## 概述

为 zephyr 增加工作空间功能——用户选择一个本地目录作为工作空间，LLM 生成的文件都落在该目录下。工作空间独立于会话，多个会话可共享同一工作空间。

## 数据模型

### 新增表 `zephyr_workspace`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | varchar(64) | UUID 主键 |
| name | varchar(128) | 工作空间名称（可填，不填则取目录最后一级名） |
| path | varchar(512) | 本地目录绝对路径 |
| user_name | varchar(64) | 所属用户 |
| created_at | bigint | 创建时间（秒） |
| updated_at | bigint | 更新时间（秒） |

### 修改表 `zephyr_conversation`

新增一列 `workspace_id varchar(64)`，可为空。空值表示未关联 workspace。

## 后端接口

### WorkspaceCtrl — `/zephyr-ui/workspace`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 当前用户 workspace 列表 |
| POST | `/create` | 新建，body: `{ name?, path }` |
| POST | `/delete` | 删除，body: `{ id }` |

### ConversationCtrl 修改

`/create` body 增加可选字段 `workspaceId`。新建会话时传入则关联 workspace。

### ContextBuilder 修改

如果会话关联了 workspace，在 system prompt 末尾追加：

```
## 工作空间
当前工作目录: /path/to/workspace
使用文件系统工具时，请将文件路径限定在此目录下。
```

## 前端改动

### InputArea — 目录选择器

在模型选择器前面加一个文件夹图标按钮：

- **未选时**：灰色文件夹图标，hover 提示"选择工作空间"
- **已选时**：显示 workspace 名称（如 `my-project`），点击弹出下拉切换

点击弹出下拉菜单：
- 已创建的 workspace 列表（名称 + 灰色路径）
- 分隔线
- "新建工作空间"选项

### 新建 workspace 对话框

两个字段：
- **名称**：选填，不填则自动取目录最后一级名
- **目录**：必填，文本输入本地目录绝对路径

### conversations store

新增 `currentWorkspace` 状态，新建会话时带上 `workspaceId`。

### 切换会话时

每个会话记住自己的 workspace，切换时自动恢复——会话详情接口返回 `workspaceId`，前端据此查找 workspace 信息。

## 行为约定

- **未选 workspace**：system prompt 不注入路径，文件 MCP 按自身默认配置工作
- **已选 workspace**：system prompt 注入路径，引导 LLM 在指定目录下操作
- **默认 workspace**：前端记住用户最后选择的 workspace，新建会话时自动关联
