# 模型思考模式参数配置

## 概述

在模型配置表单中新增思考模式（Thinking Mode）开关，支持按模型家族自动匹配参数模板，思考开启/关闭时联动显隐相关参数，并支持思考深度调节。

## 数据模型

### 后端（无变更）

`ModelConfigEntity.params` 字段不变，仍为 JSON 字符串。点号路径（如 `thinking.type`）在 `LlmClient` 注入时展开为嵌套 JSON。

### 前端：模板接口

```typescript
interface ModelTemplate {
  name: string                                    // 模板名称
  matchPattern: RegExp                            // 模型名正则匹配
  paradigm: 'thinking-type' | 'enable-thinking' | 'reasoning-effort' | 'none'
  thinkingOnParams: Record<string, string>        // 思考开启时的默认参数
  thinkingOffParams: Record<string, string>       // 思考关闭时的默认参数
  depthKey: string | null                         // 深度控制参数名
  depthValues: string[] | null                    // 枚举值（非空=下拉），null=数字输入
  depthMin: number | null                         // 数字输入最小值
  depthMax: number | null                         // 数字输入最大值
  hideOnThinking: string[]                        // 思考开启时隐藏的参数
  canDisable: boolean                             // 能否关闭思考
}
```

### 前端：点号路径与嵌套 JSON 互转

所有 params 内部存储仍用点号 path（`thinking.type`、`thinking.budget_tokens`），`buildParamsJson` 时展开为嵌套对象发给后端。

## 请求格式（逐模型核实）

### 范式 A — `thinking: {type: "enabled"/"disabled"}`（顶层对象）

| 模型 | 思考开启 | 思考关闭 | 深度控制 |
|------|---------|---------|---------|
| DeepSeek V4 Pro/Flash | `thinking: {type: "enabled"}, reasoning_effort: "high"/"max"` | `thinking: {type: "disabled"}` | `reasoning_effort` 枚举（顶层） |
| Doubao-Seed 1.6 | `thinking: {type: "enabled"}` | `thinking: {type: "disabled"}` | `"auto"` 自适应 |
| GLM-4.5+ (智谱) | `thinking: {type: "enabled"}` | `thinking: {type: "disabled"}` | 无 |
| Kimi K2 (k2.6/k2.5) | `thinking: {type: "enabled"}` | `thinking: {type: "disabled"}` | 无 |
| Claude 3.7/4.x ⚠️ | `thinking: {type: "enabled", budget_tokens: N}` | `thinking: {type: "disabled"}` | `budget_tokens` 数字（1024-128000） |
| MiMo-V2-Flash (小米) | `thinking: {type: "enabled", budget_tokens: N}` | `thinking: {type: "disabled"}` | `budget_tokens` 数字 |

> ⚠️ **Claude 需代理**：Anthropic 原生 API（`/v1/messages`）与 OpenAI `/v1/chat/completions` 格式不兼容。需通过 OpenRouter、one-api 等代理将 OpenAI 格式请求转换为 Anthropic 格式。上表为代理层暴露的 OpenAI 兼容参数格式。

### 范式 B — `enable_thinking: true/false`（顶层布尔）

| 模型 | 思考开启 | 思考关闭 | 深度控制 |
|------|---------|---------|---------|
| Qwen3 | `enable_thinking: true` | `enable_thinking: false` | `thinking_budget` 数字（顶层） |

### 范式 C — `reasoning_effort`（顶层枚举，始终思考）

| 模型 | 深度值 | 能否关闭 |
|------|-------|---------|
| OpenAI o3/o4-mini | `low` / `medium` / `high`（默认 `medium`） | 否 |
| Grok-3-mini | `low` / `high`（默认 `low`） | 否 |

### 始终思考（无法关闭）

| 模型 | 深度控制 |
|------|---------|
| MiniMax-M1 | `thinking_budget` 数字 |
| DeepSeek R1 | 无 |
| Kimi K2 (`kimi-k2-thinking`) | 无 |

### 无思考模式

DeepSeek V3、Gemma 3 — 不适用此功能。

## 参数互斥规则

思考开启时，以下参数不发送（`hideOnThinking`）：
`temperature`、`top_p`、`max_tokens`、`frequency_penalty`、`presence_penalty`

`request_timeout` 和自定义参数始终可见。

## 前端 UI 设计

### 三段式表单布局

```
┌─ 基本配置 ─────────────────────────────────────┐
│  Base URL / API Key / 模型名称 / 最大上下文      │
└────────────────────────────────────────────────┘

┌─ 思考模式 ─────────────────────────────────────┐
│  参数模板:  [自动匹配: Claude ▼]                │
│  思考模式:  [○ 关闭  ● 开启]        ← canDisable │
│  思考深度:  [4096 ▼]              ← depthKey≠null│
└────────────────────────────────────────────────┘

┌─ 模型参数 ─────────────────────────────────────┐
│  (思考关闭时显示)                                │
│  temperature / top_p / max_tokens / ...          │
│  (始终显示)                                     │
│  request_timeout / 自定义参数...                 │
└────────────────────────────────────────────────┘
```

### 交互流程

1. 用户填写 Base URL + API Key，获取模型列表
2. 选择模型名 → `matchPattern` 自动匹配模板，填充默认参数
3. 未命中则显示“自定义（无模板）”，全部手动
4. 下拉可手动切换模板
5. 思考开关切换 → 参数区域联动显隐
6. 模板预填值可被用户覆盖

### 深度控件三种变体

| 变体 | 条件 | 控件 |
|------|------|------|
| A 枚举下拉 | `depthValues` 非 null 且非空 | `<select>` 下拉 |
| B 数字输入 | `depthValues === null` 且 `depthKey !== null` | 预设 + 自定义输入 |
| C 无控件 | `depthKey === null` | 不显示 |

## LlmClient 后端适配

新增 `setNestedProperty` 方法，将 `"thinking.type"` → `"enabled"` 展开为 `bodyJson.thinking.type = "enabled"`。非点号 key 维持原逻辑。

### 变更文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `modelTemplates.ts` | 新建 | 14 个模型模板常量 |
| `ModelSettings.vue` | 修改 | 三段式表单重构 |
| `locale.ts` | 修改 | 6 个 i18n key |
| `LlmClient.java` | 修改 | 点号路径展开 |

### i18n 新增 key

```
modelConfig_thinkingMode / modelConfig_thinkingOn / modelConfig_thinkingOff
modelConfig_thinkingDepth / modelConfig_templateLabel / modelConfig_templateCustom
modelConfig_budgetCustom
```

## XML Mapper

无数据库结构变更，无需修改 Mapper XML。
