# 模型思考模式参数配置 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在模型配置表单中新增思考模式开关、模型家族参数模板自动匹配、参数联动显隐

**Architecture:** 前端新建 `modelTemplates.ts` 存放 14 个模型的思考模式参数模板；重构 `ModelSettings.vue` 表单为三段式布局（基本配置 → 思考模式 → 参数列表）；后端 `LlmClient.java` 新增点号路径展开，将 `"thinking.type"` 展开为嵌套 JSON

**Tech Stack:** Vue 3 + TypeScript（前端），Java 17 + SpringBoot + Gson（后端）

**Spec:** docs/superpowers/specs/2026-06-11-model-thinking-mode-design.md

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/resources/static/src/modelTemplates.ts` | **新建** | 14 个模型的思考模式参数模板常量 |
| `src/main/resources/static/src/i18n/locale.ts` | **修改** | 新增 6 个 i18n key（zh-CN/en-US/ja-JP） |
| `src/main/resources/static/src/views/settings/ModelSettings.vue` | **修改** | 三段式表单：基本配置 + 思考模式 + 参数列表 |
| `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java` | **修改** | 点号路径展开为嵌套 JSON |

---

### Task 1: 新建模型参数模板文件

**Files:**
- Create: `src/main/resources/static/src/modelTemplates.ts`

- [ ] **Step 1: 创建 modelTemplates.ts — 模板接口 + 14 个模型模板**

```typescript
// src/main/resources/static/src/modelTemplates.ts

export interface ModelTemplate {
  name: string
  matchPattern: RegExp
  paradigm: 'thinking-type' | 'enable-thinking' | 'reasoning-effort' | 'none'
  thinkingOnParams: Record<string, string>
  thinkingOffParams: Record<string, string>
  depthKey: string | null
  depthValues: string[] | null
  depthMin: number | null
  depthMax: number | null
  hideOnThinking: string[]
  canDisable: boolean
  requiresProxy: boolean
}

const HIDE_ON_THINKING = ['temperature', 'top_p', 'max_tokens', 'frequency_penalty', 'presence_penalty']

export const TEMPLATES: ModelTemplate[] = [
  {
    name: 'DeepSeek V4 Pro/Flash',
    matchPattern: /deepseek.*v4/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'reasoning_effort': 'high' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'reasoning_effort',
    depthValues: ['high', 'max'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'DeepSeek R1',
    matchPattern: /deepseek.*r1/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'DeepSeek V3',
    matchPattern: /deepseek.*v3(?:\.\d+)?(?!\d|.*v4)/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: [],
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'OpenAI o3/o4-mini',
    matchPattern: /\bo(?:3|4)-mini\b/i,
    paradigm: 'reasoning-effort',
    thinkingOnParams: { 'reasoning_effort': 'medium' },
    thinkingOffParams: {},
    depthKey: 'reasoning_effort',
    depthValues: ['low', 'medium', 'high'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Claude 3.7/4.x',
    matchPattern: /claude/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'thinking.budget_tokens': '16000' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'thinking.budget_tokens',
    depthValues: null,
    depthMin: 1024, depthMax: 128000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: true,
  },
  {
    name: 'Doubao-Seed 1.6',
    matchPattern: /doubao.*seed/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'GLM-4.5+ (智谱)',
    matchPattern: /glm(?:-\d)?/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Kimi K2 (k2.6/k2.5)',
    matchPattern: /kimi.*k2\.[56]/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Kimi K2 Thinking',
    matchPattern: /kimi.*k2.*think/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Qwen3',
    matchPattern: /qwen3/i,
    paradigm: 'enable-thinking',
    thinkingOnParams: { 'enable_thinking': 'true', 'thinking_budget': '2048' },
    thinkingOffParams: { 'enable_thinking': 'false' },
    depthKey: 'thinking_budget',
    depthValues: null,
    depthMin: 0, depthMax: 16000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'MiniMax-M1',
    matchPattern: /minimax.*m1/i,
    paradigm: 'none',
    thinkingOnParams: { 'thinking_budget': '4096' },
    thinkingOffParams: {},
    depthKey: 'thinking_budget',
    depthValues: null,
    depthMin: 0, depthMax: 40000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'MiMo-V2-Flash (小米)',
    matchPattern: /mimo/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'thinking.budget_tokens': '2048' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'thinking.budget_tokens',
    depthValues: null,
    depthMin: 0, depthMax: 16000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Grok-3-mini',
    matchPattern: /grok.*3.*mini/i,
    paradigm: 'reasoning-effort',
    thinkingOnParams: { 'reasoning_effort': 'low' },
    thinkingOffParams: {},
    depthKey: 'reasoning_effort',
    depthValues: ['low', 'high'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Gemma 3',
    matchPattern: /gemma.*3/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: [],
    canDisable: false,
    requiresProxy: false,
  },
]

export function matchTemplate(modelName: string): ModelTemplate | null {
  for (const t of TEMPLATES) {
    if (t.matchPattern.test(modelName)) return t
  }
  return null
}

export function getTemplate(name: string): ModelTemplate | undefined {
  return TEMPLATES.find(t => t.name === name)
}
```

- [ ] **Step 2: 验证 TypeScript 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit src/modelTemplates.ts 2>&1 | head -20
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/modelTemplates.ts
git commit -m "$(cat <<'EOF'
feat: 新建模型思考模式参数模板文件，包含 14 个模型家族模板

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 添加 i18n 国际化 key

**Files:**
- Modify: `src/main/resources/static/src/i18n/locale.ts`

- [ ] **Step 1: 在 zh-CN 模型配置区域末尾添加 7 个 key**

在 `"modelConfig_ctxCustom": "自定义"` 之后（该行后紧跟 `},`）添加：

```typescript
"modelConfig_thinkingMode": "思考模式",
"modelConfig_thinkingModeOn": "开启",
"modelConfig_thinkingModeOff": "关闭",
"modelConfig_thinkingDepth": "思考深度",
"modelConfig_templateLabel": "参数模板",
"modelConfig_templateCustom": "自定义（无模板）",
"modelConfig_budgetCustom": "自定义 token 数",
```

- [ ] **Step 2: 在 en-US 模型配置区域末尾添加**

```typescript
"modelConfig_thinkingMode": "Thinking Mode",
"modelConfig_thinkingModeOn": "On",
"modelConfig_thinkingModeOff": "Off",
"modelConfig_thinkingDepth": "Thinking Depth",
"modelConfig_templateLabel": "Parameter Template",
"modelConfig_templateCustom": "Custom (no template)",
"modelConfig_budgetCustom": "Custom token count",
```

- [ ] **Step 3: 在 ja-JP 模型配置区域末尾添加**

```typescript
"modelConfig_thinkingMode": "思考モード",
"modelConfig_thinkingModeOn": "オン",
"modelConfig_thinkingModeOff": "オフ",
"modelConfig_thinkingDepth": "思考深度",
"modelConfig_templateLabel": "パラメータテンプレート",
"modelConfig_templateCustom": "カスタム（テンプレートなし）",
"modelConfig_budgetCustom": "カスタムトークン数",
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/i18n/locale.ts
git commit -m "$(cat <<'EOF'
feat: 模型配置新增思考模式相关 i18n key（中/英/日）

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 重构 ModelSettings.vue — 数据与逻辑层

**Files:**
- Modify: `src/main/resources/static/src/views/settings/ModelSettings.vue`

- [ ] **Step 1: 替换 import 区块**

将现有的 `import { ref, onMounted } from 'vue'` 替换为：

```typescript
import { ref, onMounted, watch, computed } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { matchTemplate, getTemplate, type ModelTemplate } from '@/modelTemplates'
```

- [ ] **Step 2: 在 `CTX_PRESETS` 常量之后、`resolveMaxCtx` 之前添加工具函数**

```typescript
function flattenToNested(flat: Record<string, string>): Record<string, any> {
  const result: Record<string, any> = {}
  for (const [k, v] of Object.entries(flat)) {
    if (!v) continue
    const num = Number(v)
    const val = isNaN(num) ? v : num
    if (k.includes('.')) {
      const parts = k.split('.')
      let cur = result
      for (let i = 0; i < parts.length - 1; i++) {
        if (!cur[parts[i]] || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {}
        cur = cur[parts[i]]
      }
      cur[parts[parts.length - 1]] = val
    } else {
      result[k] = val
    }
  }
  return result
}

function nestedToFlatten(obj: Record<string, any>, prefix = ''): Record<string, string> {
  const result: Record<string, string> = {}
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      Object.assign(result, nestedToFlatten(v, key))
    } else {
      result[key] = String(v)
    }
  }
  return result
}
```

- [ ] **Step 3: 在 `setMaxCtxFromStored` 函数之后添加思考模式相关状态**

```typescript
const thinkingOn = ref(false)
const matchedTemplate = ref<ModelTemplate | null>(null)
const selectedTemplateName = ref('')
const depthPreset = ref('')
const depthCustom = ref('')

const templateOptions = computed(() => {
  return [
    { label: langData.modelConfig_templateCustom, value: '__custom__' },
    ...TEMPLATES.map(t => ({
      label: (t.requiresProxy ? '⚠️ ' : '') + t.name,
      value: t.name,
    })),
  ]
})

function applyTemplate(t: ModelTemplate | null) {
  if (!t) return
  matchedTemplate.value = t
  thinkingOn.value = true
  // 注入模板的思考开启参数
  for (const [k, v] of Object.entries(t.thinkingOnParams)) {
    const existing = params.value.find(p => p.key === k)
    if (existing) existing.value = v
    else params.value.push({ key: k, value: v, tip: null, isPreset: false })
  }
  // 预填深度值
  if (t.depthKey) {
    const dv = t.thinkingOnParams[t.depthKey]
    if (dv) {
      if (t.depthValues) {
        depthPreset.value = dv
        depthCustom.value = ''
      } else {
        depthPreset.value = '__custom__'
        depthCustom.value = dv
      }
    }
  }
}

function clearThinkingParams() {
  const thinkingKeys = ['thinking.type', 'thinking.budget_tokens', 'enable_thinking', 'reasoning_effort', 'thinking_budget']
  params.value = params.value.filter(p => !thinkingKeys.includes(p.key))
  depthPreset.value = ''
  depthCustom.value = ''
}

function onToggleThinking(val: boolean) {
  thinkingOn.value = val
  const t = matchedTemplate.value
  if (!t) return
  clearThinkingParams()
  if (val) {
    for (const [k, v] of Object.entries(t.thinkingOnParams)) {
      params.value.push({ key: k, value: v, tip: null, isPreset: false })
    }
    if (t.depthKey) {
      const dv = t.thinkingOnParams[t.depthKey]
      if (dv) {
        if (t.depthValues) { depthPreset.value = dv }
        else { depthPreset.value = '__custom__'; depthCustom.value = dv }
      }
    }
  } else {
    for (const [k, v] of Object.entries(t.thinkingOffParams)) {
      if (v) params.value.push({ key: k, value: v, tip: null, isPreset: false })
    }
  }
}

function onTemplateChange(name: string) {
  selectedTemplateName.value = name
  const t = getTemplate(name)
  if (t) {
    clearThinkingParams()
    applyTemplate(t)
  } else {
    matchedTemplate.value = null
    thinkingOn.value = false
    clearThinkingParams()
  }
}

function onDepthChange(val: string) {
  const t = matchedTemplate.value
  if (!t || !t.depthKey) return
  const idx = params.value.findIndex(p => p.key === t.depthKey)
  const effectiveVal = val === '__custom__' ? depthCustom.value : val
  if (idx >= 0) {
    params.value[idx].value = effectiveVal
  } else {
    params.value.push({ key: t.depthKey!, value: effectiveVal, tip: null, isPreset: false })
  }
}

function onDepthCustomChange(val: string) {
  const t = matchedTemplate.value
  if (!t || !t.depthKey) return
  const idx = params.value.findIndex(p => p.key === t.depthKey)
  if (idx >= 0) params.value[idx].value = val
  else params.value.push({ key: t.depthKey!, value: val, tip: null, isPreset: false })
}

// 模型名变更时自动匹配模板
watch(name, (val) => {
  const t = matchTemplate(val)
  clearThinkingParams()
  if (t) {
    selectedTemplateName.value = t.name
    applyTemplate(t)
  } else {
    matchedTemplate.value = null
    selectedTemplateName.value = '__custom__'
    thinkingOn.value = false
  }
})

const visibleParams = computed(() => {
  const thinkingKeys = ['thinking.type', 'thinking.budget_tokens', 'enable_thinking', 'reasoning_effort', 'thinking_budget']
  if (!thinkingOn.value || !matchedTemplate.value) {
    return params.value.filter(p => !thinkingKeys.includes(p.key))
  }
  return params.value.filter(p => {
    if (thinkingKeys.includes(p.key)) return false
    return !matchedTemplate.value!.hideOnThinking.includes(p.key)
  })
})
```

- [ ] **Step 4: 修改 `buildParamsJson` 函数使用 `flattenToNested`**

将原 `buildParamsJson` 替换为：

```typescript
function buildParamsJson(): string {
  const flat: Record<string, string> = {}
  for (const p of params.value) {
    if (p.value === '') continue
    flat[p.key] = p.value
  }
  const nested = flattenToNested(flat)
  return Object.keys(nested).length > 0 ? JSON.stringify(nested) : ''
}
```

- [ ] **Step 5: 修改 `initParams` 函数支持嵌套 JSON 回填 + 思考参数识别**

```typescript
function initParams(loadedParams?: Record<string, any>) {
  // 先将预置参数默认值展开
  params.value = PRESET_PARAMS.map(p => ({
    key: p.key, value: p.default, tip: p.tip, isPreset: true,
  }))
  if (!loadedParams) {
    matchedTemplate.value = null
    selectedTemplateName.value = '__custom__'
    thinkingOn.value = false
    return
  }
  // 展开嵌套 JSON 为点号路径
  const flat = nestedToFlatten(loadedParams)
  // 覆盖预置参数
  for (const p of params.value) {
    if (flat[p.key] != null) p.value = String(flat[p.key])
  }
  // 加载自定义参数
  for (const [k, v] of Object.entries(flat)) {
    if (!PRESET_PARAMS.find(p => p.key === k) && !params.value.find(p => p.key === k)) {
      params.value.push({ key: k, value: String(v), tip: null, isPreset: false })
    }
  }
  // 检测思考模式
  checkThinkingState(flat)
}

function checkThinkingState(flat: Record<string, string>) {
  const t = matchTemplate(name.value || '')
  if (!t) {
    matchedTemplate.value = null
    selectedTemplateName.value = '__custom__'
    thinkingOn.value = false
    return
  }
  matchedTemplate.value = t
  selectedTemplateName.value = t.name
  if (t.paradigm === 'reasoning-effort' || t.paradigm === 'none') {
    thinkingOn.value = !t.canDisable ? true : !!(flat[t.thinkingOnParams ? Object.keys(t.thinkingOnParams)[0] : ''] || flat['reasoning_effort'])
  } else if (t.paradigm === 'thinking-type') {
    thinkingOn.value = flat['thinking.type'] !== 'disabled'
  } else if (t.paradigm === 'enable-thinking') {
    thinkingOn.value = flat['enable_thinking'] === 'true'
  }
  // 回填深度值
  if (t.depthKey && flat[t.depthKey]) {
    if (t.depthValues) {
      depthPreset.value = flat[t.depthKey]
    } else {
      depthPreset.value = '__custom__'
      depthCustom.value = flat[t.depthKey]
    }
  }
}
```

- [ ] **Step 6: 修改 `resetForm` 函数重置思考模式状态**

在 `resetForm` 函数末尾，`initParams()` 之前或之后添加：

```typescript
thinkingOn.value = false
matchedTemplate.value = null
selectedTemplateName.value = '__custom__'
depthPreset.value = ''
depthCustom.value = ''
```

- [ ] **Step 7: 修改 `startEdit` 函数**

在 `startEdit` 函数中，将 `initParams(parseParamsJson(m.params))` 保持不变（Step 5 已将检测逻辑内置于 `initParams`）。

- [ ] **Step 8: 提交**

```bash
git add src/main/resources/static/src/views/settings/ModelSettings.vue
git commit -m "$(cat <<'EOF'
feat: 模型配置表单重构 — 新增思考模式开关、模板匹配与参数联动

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 重构 ModelSettings.vue — 模板层（UI）

**Files:**
- Modify: `src/main/resources/static/src/views/settings/ModelSettings.vue`

- [ ] **Step 1: 在“基本配置”区域末尾（`config-block` 结束 `</div>` 之后，“参数”section 之前）插入思考模式区域**

在 `</div>` (基本配置 config-block 结束) 之后、`<div class="section-title">{{ langData.modelConfig_params }}</div>` 之前插入：

```html
<div class="section-title">{{ langData.modelConfig_thinkingMode }}</div>
<div class="config-block">
  <div class="field">
    <label class="field-label">{{ langData.modelConfig_templateLabel }}</label>
    <select class="field-input" v-model="selectedTemplateName" @change="onTemplateChange(($event.target as HTMLSelectElement).value)">
      <option v-for="o in templateOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
  </div>
  <div v-if="matchedTemplate" class="field">
    <label class="field-label">{{ langData.modelConfig_thinkingMode }}</label>
    <div class="toggle-row">
      <span v-if="!matchedTemplate.canDisable" class="toggle-hint">
        {{ thinkingOn ? langData.modelConfig_thinkingModeOn : langData.modelConfig_thinkingModeOff }}（始终{{ thinkingOn ? '开启' : '关闭' }}）
      </span>
      <label v-else class="toggle-switch">
        <input type="checkbox" :checked="thinkingOn" @change="onToggleThinking(($event.target as HTMLInputElement).checked)" />
        <span class="toggle-slider"></span>
      </label>
    </div>
  </div>
  <div v-if="matchedTemplate && matchedTemplate.depthKey" class="field">
    <label class="field-label">{{ langData.modelConfig_thinkingDepth }}</label>
    <template v-if="matchedTemplate.depthValues">
      <select class="field-input" :value="depthPreset" @change="onDepthChange(($event.target as HTMLSelectElement).value)">
        <option v-for="v in matchedTemplate.depthValues" :key="v" :value="v">{{ v }}</option>
      </select>
    </template>
    <template v-else>
      <div class="input-row">
        <input v-if="depthPreset === '__custom__'" class="field-input" v-model="depthCustom"
               :placeholder="langData.modelConfig_budgetCustom"
               @input="onDepthCustomChange(($event.target as HTMLInputElement).value)" />
        <select class="field-input" v-model="depthPreset" @change="onDepthChange(($event.target as HTMLSelectElement).value)">
          <option value="512">512</option>
          <option value="1024">1K</option>
          <option value="2048">2K</option>
          <option value="4096">4K</option>
          <option value="8192">8K</option>
          <option value="16000">16K</option>
          <option value="32000">32K</option>
          <option value="__custom__">{{ langData.modelConfig_ctxCustom }}</option>
        </select>
      </div>
    </template>
  </div>
</div>
```

- [ ] **Step 2: 修改参数区域标题，从 `visibleParams` 渲染而非 `params`**

将参数列表的 `v-for="(p, i) in params"` 改为 `v-for="(p, i) in visibleParams"`。

同时将 `removeParam(i)` 的调用改为对应 `params` 中的正确索引——因为 `visibleParams` 是过滤后的，删除时需要找到 `params` 中的实际索引。修改删除逻辑：

```html
<button class="param-delete" @click="removeParam(params.findIndex(x => x.key === p.key))" title="删除">
```

- [ ] **Step 3: 在 style 块末尾添加思考模式相关样式**

```css
/* 思考模式相关样式 */
.toggle-row { display: flex; align-items: center; gap: 10px; }
.toggle-hint { font-size: 13px; color: var(--el-text-color-secondary); font-style: italic; }
.toggle-switch { position: relative; display: inline-block; width: 40px; height: 22px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 22px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 3px; bottom: 3px; background: #fff; border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(18px); }

/* 暗黑模式适配 */
html.dark .toggle-slider::before { background: var(--el-bg-color); }
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/settings/ModelSettings.vue
git commit -m "$(cat <<'EOF'
feat: 模型配置表单新增思考模式 UI 区域（模板选择/开关/深度控件）

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 修改 LlmClient.java 支持点号路径展开

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java`

- [ ] **Step 1: 在 params 注入循环处替换为支持点号路径的逻辑**

将第 56-73 行（`// 注入模型参数` 注释块）替换为：

```java
// 注入模型参数（temperature、top_p、max_tokens 等 + 点号路径展开）
Map<String, Object> params = parseParams(model.getParams());
if (params != null) {
    for (Map.Entry<String, Object> e : params.entrySet()) {
        if ("request_timeout".equals(e.getKey())) continue;
        if (e.getKey().contains(".")) {
            setNestedProperty(bodyJson, e.getKey(), e.getValue());
        } else {
            addJsonProperty(bodyJson, e.getKey(), e.getValue());
        }
    }
}
```

- [ ] **Step 2: 新增 `setNestedProperty` 和 `addJsonProperty` 私有方法**

在 `getTimeoutSeconds` 方法之后添加：

```java
private void setNestedProperty(JsonObject root, String path, Object value) {
    String[] parts = path.split("\\.");
    JsonObject cur = root;
    for (int i = 0; i < parts.length - 1; i++) {
        JsonElement child = cur.get(parts[i]);
        if (child == null || !child.isJsonObject()) {
            JsonObject next = new JsonObject();
            cur.add(parts[i], next);
            cur = next;
        } else {
            cur = child.getAsJsonObject();
        }
    }
    addJsonProperty(cur, parts[parts.length - 1], value);
}

private void addJsonProperty(JsonObject obj, String key, Object value) {
    if (value instanceof Number) {
        double d = ((Number) value).doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && d <= Long.MAX_VALUE) {
            obj.addProperty(key, (long) d);
        } else {
            obj.addProperty(key, d);
        }
    } else if (value instanceof Boolean) {
        obj.addProperty(key, (Boolean) value);
    } else {
        obj.addProperty(key, String.valueOf(value));
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java
git commit -m "$(cat <<'EOF'
feat: LlmClient 支持点号路径参数展开为嵌套 JSON

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 构建验证

- [ ] **Step 1: 前端类型检查**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: 无类型错误

- [ ] **Step 2: 前端构建**

```bash
cd src/main/resources/static && npm run build
```

Expected: build 成功，输出到 `zephyr-ui/`

- [ ] **Step 3: 后端编译**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr
mvn compile -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交（如有构建产物变更）**

如果构建生成了需要提交的文件，提交它们。
