# 状态栏改进设计

## 改动总览

| 模块 | 文件 | 改动 |
|------|------|------|
| 后端 | ModelConfigEntity.java | 加 `maxContextTokens` 字段 |
| 后端 | ModelConfigMapper.xml（3 方言 + common）| DDL + DML 加新列 |
| 后端 | ModelConfigServiceImpl.java | 创建模型时自动探测 maxContextTokens |
| 后端 | zephyr-zh-CN.sql | ALTER TABLE ADD COLUMN 增量迁移 |
| 前端 | StatusBar.vue | 重排布局：模型名后紧跟上下文占比，去掉 MCP/Skill |
| 前端 | settings.ts | model 增加 maxContextTokens，context 取值改用当前模型上限 |
| 前端 | ModelSettings.vue | 编辑模型时手动填 maxContextTokens |

## 状态栏新布局

```
[模型] DeepSeek-V3  [▇▇▇▇▇▇░░░░] 23.5K/128K 18%    (右侧无内容)
```

- 模型名和占比紧邻
- 删除 MCP、Skill 两项
- 右侧 spacer 删除（所有内容左对齐）

## 上下文占比颜色渐变

使用 HSL 色相渐变：低占比绿色 → 中等黄色 → 高占比红色。

| 占比 | 颜色 | 含义 |
|------|------|------|
| 0-40% | `#5db872` (success) | 安全 |
| 40-70% | `#e8a55a` (accent-amber) | 中等 |
| 70-100% | `#c64545` (error) | 危险 |

颜色在边界处平滑过渡，计算方式：

```typescript
function getContextColor(percent: number): string {
  if (percent <= 40) return '#5db872'
  if (percent <= 70) {
    // 40-70%: green → amber
    const t = (percent - 40) / 30
    return hslInterpolate('#5db872', '#e8a55a', t)
  }
  // 70-100%: amber → red
  const t = Math.min((percent - 70) / 30, 1)
  return hslInterpolate('#e8a55a', '#c64545', t)
}
```

## 模型上下文大小自动探测

创建模型时，后端尝试调模型 API 的 `/v1/models`（OpenAI 兼容接口），从中提取模型元数据：

```java
// 伪代码
try {
    String url = baseUrl + "/v1/models";
    // 带 apiKey 的 GET 请求
    // 从响应中匹配当前模型名的 context_window 字段
    // 如果取到，设置 maxContextTokens
} catch (Exception e) {
    // 探测不到，maxContextTokens 为 null
    log.info("无法自动探测上下文大小");
}
```

### 探测逻辑

1. 请求 `{baseUrl}/v1/models`，Header 带 `Authorization: Bearer {apiKey}`
2. 解析响应 JSON，找到 `data[].id` 匹配当前模型的那条
3. 读取 `context_window` 或 `max_input_tokens` 字段
4. 取到则写入 `maxContextTokens`，取不到则为 null

### 手动配置

如果自动探测失败，用户在前端编辑模型时手动填写 maxContextTokens 字段。

前端模型配置表单在 API Base / API Key 下方增加：

```
最大上下文 (tokens): [        ]  (可选，自动探测失败时手动填写)
```

## 后端改动细节

### Entity

```java
// ModelConfigEntity.java 新增
private Long maxContextTokens;
```

### Mapper XML

**DDL（3 方言 `createModelConfigsTable`）：** 全部在 `api_key_encrypted text,` 后加：
```sql
max_context_tokens bigint,
```

**增量 DDL（`zephyr-zh-CN.sql`）：**
```sql
alter table model_configs add column if not exists max_context_tokens bigint;
```

**Common DML：** insert/update/select 语句加 `max_context_tokens as maxContextTokens` 或 `#{maxContextTokens}`。

### Service

- `create()` 中：保存模型后尝试自动探测 maxContextTokens，探测成功则 `updateMaxContextTokens()` 写入
- `list()` 返回的实体包含 maxContextTokens

## 前端改动

### StatusBar.vue

模板简化：
```html
<div class="status-bar">
  <div class="status-item">
    <Icon icon="lucide:bot" class="s-icon" />
    <span>{{ settingsStore.currentModel }}</span>
  </div>

  <div class="ctx-bar-wrap">
    <div class="ctx-bar">
      <div class="ctx-fill" :style="ctxFillStyle"></div>
    </div>
    <span class="ctx-text">{{ ctxUsedStr }} / {{ ctxTotalStr }}</span>
    <span class="ctx-pct" :style="{ color: ctxColor }">{{ ctxPercent }}%</span>
  </div>
</div>
```

### settings.ts

- ModelConfig 类型加 `maxContextTokens?: number`
- `contextTotal` 计算改用当前模型的 `maxContextTokens`，如果没配置则用 131072 默认
- 添加 `loadContextUsage()` 方法，调 `/chat/context-usage` 获取上下文用量

## 不改的文件

- ChatServiceImpl（contextUsage 方法已存在）
- MCP/Skill 的 store 方法保留（其他页面仍在使用）
- 会话/操作命令逻辑

## 测试验证

1. **占比显示**：聊几轮后状态栏显示当前占比
2. **颜色渐变**：占比从低到高颜色从绿→黄→红变化
3. **模型配置**：新增模型后自动探测 maxContextTokens
4. **手动填写**：去掉 API Key 后新增模型，再手动编辑 maxContextTokens
5. **MCP/Skill 已删除**：状态栏不显示这两项
