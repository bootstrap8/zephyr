# `/` 命令输入 设计规格

## 概述

输入框输入 `/` 时弹出命令菜单，支持搜索和键盘导航。命令分三类：切换类（弹出列表选择）、展示类（浮层查看）、操作类（直接执行）。

## 命令菜单

```
┌────────────────────────────────┐
│ 🔍 搜索命令...                  │
├────────────────────────────────┤
│ 模型                            │
│  /model        切换对话模型  →    │
├────────────────────────────────┤
│ 能力                            │
│  /mcp          MCP工具列表  →    │
│  /skills       可用技能      →   │
│  /memory       用户记忆      →   │
├────────────────────────────────┤
│ 会话                            │
│  /resume       恢复之前的对话  →  │
│  /context      上下文占比      →   │
├────────────────────────────────┤
│ 操作                            │
│  /clear        清空当前对话       │
│  /help         查看帮助          │
└────────────────────────────────┘
```

## 各命令行为

| 命令 | 类型 | 行为 |
|------|------|------|
| `/model` | 切换 | 弹出模型选择浮层（从 settingsStore.models 加载），选择后调 setDefaultModelRemote() |
| `/mcp` | 展示 | 弹出 MCP 工具列表浮层（调 `/mcp/server/list` 接口，显示 server 名+工具数+状态） |
| `/skills` | 展示 | 弹出技能列表浮层（调 `/skill/list` 接口，显示 name+description+source） |
| `/memory` | 展示 | 弹出记忆列表浮层（调 `/memory/list` 接口），点击一条 → router.push 跳转编辑页 |
| `/resume` | 切换 | 弹出最近会话列表（调 `/conversations/list` 接口），选择后切换 convStore.currentId |
| `/context` | 展示 | 弹出上下文占比浮层（调 `GET /chat/context-usage?conversationId=xxx`），条形图展示 |
| `/clear` | 操作 | `chatStore.clearMessages()`，清空当前对话 |
| `/help` | 操作 | `router.push('/help')` 跳转帮助页 |

## 交互细节

- 输入 `/` 弹出菜单，继续输入实时过滤（模糊匹配命令名和描述）
- `↑↓` 键盘导航，`Enter` 选择，`Esc` 关闭
- 菜单按分组排列，每组之间分隔线
- 每个命令右侧显示辅助信息（当前模型名、工具数量、记忆条数等 badge）

## 新增 API

### GET /chat/context-usage?conversationId={id}

返回当前会话上下文的各部分 token 估算：

```json
{
  "systemPrompt": 1200,
  "history": 4500,
  "skillContent": 0,
  "memoryContent": 0,
  "toolDefinitions": 800,
  "total": 6500
}
```

## 新增/修改文件

```
static/src/views/chat/
├── InputArea.vue         # 修改：增加 / 命令菜单（SlashMenu 嵌入）
├── SlashMenu.vue         # 🆕 命令菜单组件
├── ModelPicker.vue       # 🆕 模型切换浮层（复用 InputArea 已有的下拉逻辑）
├── McpListPanel.vue      # 🆕 MCP 工具列表浮层
├── SkillsListPanel.vue   # 🆕 技能列表浮层
├── MemoryListPanel.vue   # 🆕 记忆列表浮层
├── ResumePanel.vue       # 🆕 恢复对话浮层
└── ContextPanel.vue      # 🆕 上下文占比浮层

chat/ctrl/ChatCtrl.java   # 修改：新增 context-usage 接口
```
