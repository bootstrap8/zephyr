<script lang="ts" setup>
import { ref, onMounted, watch, computed } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { matchTemplate, getTemplate, TEMPLATES, type ModelTemplate } from '@/modelTemplates'

const langData = getLangData()
const settingsStore = useSettingsStore()
const showForm = ref(false)
const name = ref('')
const baseUrl = ref('')
const apiKey = ref('')
const apiKeyShown = ref('')
const hasExistingKey = ref(false)
const maxCtxPreset = ref('')
const maxCtxCustom = ref('')
const editId = ref<string | null>(null)
const fetching = ref(false)
const modelType = ref('llm')
const dimensions = ref<number | undefined>(undefined)

const CTX_PRESETS = [
  { label: '128K', value: '131072' },
  { label: '256K', value: '262144' },
  { label: '1M', value: '1048576' },
]

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

function resolveMaxCtx(): string {
  if (maxCtxPreset.value === 'custom') return maxCtxCustom.value
  return maxCtxPreset.value
}

function setMaxCtxFromStored(val: string | number | undefined) {
  if (!val) { maxCtxPreset.value = ''; maxCtxCustom.value = ''; return }
  const s = String(val)
  const preset = CTX_PRESETS.find(p => p.value === s)
  if (preset) { maxCtxPreset.value = preset.value; maxCtxCustom.value = '' }
  else { maxCtxPreset.value = 'custom'; maxCtxCustom.value = s }
}

const thinkingOn = ref(false)
const matchedTemplate = ref<ModelTemplate | null>(null)
const selectedTemplateName = ref('__custom__')
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
  if (t.paradigm === 'reasoning-effort') {
    thinkingOn.value = true
  } else if (t.paradigm === 'none') {
    const hasThinking = Object.keys(t.thinkingOnParams).length > 0 || t.hideOnThinking.length > 0
    thinkingOn.value = !t.canDisable && hasThinking
  } else {
    thinkingOn.value = true
  }
  for (const [k, v] of Object.entries(t.thinkingOnParams)) {
    const existing = params.value.find(p => p.key === k)
    if (existing) existing.value = v
    else params.value.push({ key: k, value: v, tip: null, isPreset: false })
  }
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

function onModelNameInput() {
  const t = matchTemplate(name.value)
  if (t) {
    clearThinkingParams()
    applyTemplate(t)
    selectedTemplateName.value = t.name
  }
}

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

const PRESET_PARAMS = [
  { key: 'temperature', default: '0.7' },
  { key: 'top_p', default: '1.0' },
  { key: 'max_tokens', default: '4096' },
  { key: 'frequency_penalty', default: '0' },
  { key: 'presence_penalty', default: '0' },
  { key: 'reasoning_effort', default: 'medium' },
  { key: 'request_timeout', default: '120' }
]
const PARAM_TIP_KEYS: Record<string, string> = {
  temperature: 'modelConfig_paramTip_temperature',
  top_p: 'modelConfig_paramTip_topP',
  max_tokens: 'modelConfig_paramTip_maxTokens',
  frequency_penalty: 'modelConfig_paramTip_frequencyPenalty',
  presence_penalty: 'modelConfig_paramTip_presencePenalty',
  reasoning_effort: 'modelConfig_paramTip_reasoningEffort',
  request_timeout: 'modelConfig_paramTip_requestTimeout',
}
function paramTip(key: string): string {
  return (langData as any)[PARAM_TIP_KEYS[key]] || ''
}
const params = ref<{ key: string; value: string; tip: string | null; isPreset: boolean }[]>([])
const showAddParam = ref(false)
const newParamKey = ref('')
const newParamVal = ref('')

function initParams(loadedParams?: Record<string, any>) {
  params.value = PRESET_PARAMS.map(p => ({
    key: p.key, value: p.default, tip: paramTip(p.key), isPreset: true,
  }))
  if (!loadedParams) {
    matchedTemplate.value = null
    selectedTemplateName.value = '__custom__'
    thinkingOn.value = false
    return
  }
  const flat = nestedToFlatten(loadedParams)
  for (const p of params.value) {
    if (flat[p.key] != null) p.value = String(flat[p.key])
  }
  for (const [k, v] of Object.entries(flat)) {
    const existing = params.value.find(p => p.key === k)
    if (existing) {
      existing.value = String(v)
    } else if (!PRESET_PARAMS.find(p => p.key === k)) {
      params.value.push({ key: k, value: String(v), tip: null, isPreset: false })
    }
  }
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
  if (t.paradigm === 'reasoning-effort') {
    thinkingOn.value = true
  } else if (t.paradigm === 'none') {
    const hasThinking = Object.keys(t.thinkingOnParams).length > 0 || t.hideOnThinking.length > 0
    thinkingOn.value = !t.canDisable && hasThinking
  } else if (t.paradigm === 'thinking-type') {
    thinkingOn.value = flat['thinking.type'] === 'enabled'
  } else if (t.paradigm === 'enable-thinking') {
    thinkingOn.value = flat['enable_thinking'] === 'true'
  }
  if (t.depthKey && flat[t.depthKey]) {
    if (t.depthValues) {
      depthPreset.value = flat[t.depthKey]
    } else {
      depthPreset.value = '__custom__'
      depthCustom.value = flat[t.depthKey]
    }
  } else {
    depthPreset.value = ''
    depthCustom.value = ''
  }
}

function buildParamsJson(): string {
  const hiddenKeys = (thinkingOn.value && matchedTemplate.value)
    ? new Set(matchedTemplate.value.hideOnThinking)
    : new Set<string>()
  const flat: Record<string, string> = {}
  for (const p of params.value) {
    if (p.value === '' || hiddenKeys.has(p.key)) continue
    flat[p.key] = p.value
  }
  const nested = flattenToNested(flat)
  return Object.keys(nested).length > 0 ? JSON.stringify(nested) : ''
}

function parseParamsJson(raw?: string): Record<string, any> | undefined {
  if (!raw) return undefined
  try { return JSON.parse(raw) } catch { return undefined }
}

onMounted(() => { settingsStore.loadModels() })

async function add() {
  if (!name.value.trim()) return
  const paramsJson = buildParamsJson()
  if (editId.value) {
    await settingsStore.updateModelRemote(editId.value, name.value.trim(), baseUrl.value.trim(), apiKey.value, resolveMaxCtx(), paramsJson, modelType.value, dimensions.value)
  } else {
    await settingsStore.addModelRemote(name.value.trim(), baseUrl.value.trim(), apiKey.value, resolveMaxCtx(), paramsJson, modelType.value, dimensions.value)
  }
  resetForm()
}

function resetForm() {
  name.value = ''
  baseUrl.value = ''
  apiKey.value = ''
  apiKeyShown.value = ''
  hasExistingKey.value = false
  maxCtxPreset.value = ''
  maxCtxCustom.value = ''
  editId.value = null
  showForm.value = false
  modelType.value = 'llm'
  dimensions.value = undefined
  initParams()
  thinkingOn.value = false
  matchedTemplate.value = null
  selectedTemplateName.value = '__custom__'
  depthPreset.value = ''
  depthCustom.value = ''
}

function startEdit(m: any) {
  editId.value = m.id
  name.value = m.name
  baseUrl.value = m.baseUrl || ''
  setMaxCtxFromStored(m.maxContextTokens)
  if (m.apiKey) {
    hasExistingKey.value = true
    apiKey.value = ''
    apiKeyShown.value = '••••••••'
  } else {
    hasExistingKey.value = false
    apiKey.value = ''
    apiKeyShown.value = ''
  }
  initParams(parseParamsJson(m.params))
  modelType.value = m.modelType || 'llm'
  dimensions.value = m.dimensions || undefined
  showForm.value = true
}

function cancelForm() { resetForm() }

function onApiKeyFocus() {
  if (hasExistingKey.value && apiKeyShown.value !== '') {
    apiKeyShown.value = ''
  }
}

function onApiKeyBlur() {
  if (hasExistingKey.value && apiKeyShown.value === '') {
    apiKeyShown.value = '••••••••'
  }
}

function clearApiKey() {
  hasExistingKey.value = false
  apiKey.value = ''
  apiKeyShown.value = ''
}

function apiKeyDisplayValue(): string {
  return hasExistingKey.value ? apiKeyShown.value : apiKey.value
}

async function fetchModels() {
  if (!baseUrl.value.trim() || (!apiKey.value && !hasExistingKey.value)) { msg(langData.modelConfig_fetchFail, 'warning'); return }
  fetching.value = true
  const models = await settingsStore.fetchModels(baseUrl.value.trim(), apiKey.value, editId.value || undefined)
  fetching.value = false
  if (models.length > 0) {
    const sel = document.getElementById('modelSelect') as HTMLSelectElement | null
    if (sel) {
      sel.innerHTML = '<option value="">' + langData.modelConfig_selectModel + '</option>' + models.map(m => `<option value="${m.id}">${m.id}</option>`).join('')
      sel.style.display = 'block'
      const input = document.getElementById('modelNameInput') as HTMLInputElement | null
      if (input) input.style.display = 'none'
      sel.focus()
    }
  } else {
    msg(langData.modelConfig_fetchFail, 'warning')
  }
}

function onModelSelect(val: string) {
  if (val) {
    name.value = val
    onModelNameInput()
    const sel = document.getElementById('modelSelect') as HTMLSelectElement | null
    const input = document.getElementById('modelNameInput') as HTMLInputElement | null
    if (sel) sel.style.display = 'none'
    if (input) input.style.display = 'block'
  }
}


async function removeModel(id: string) { await settingsStore.deleteModelRemote(id) }
async function setDefault(id: string) { await settingsStore.setDefaultModelRemote(id) }

async function onSetCurrent(name: string) {
  settingsStore.setModel(name)
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) await settingsStore.setDefaultModelRemote(m.id)
}

function addCustomParam() {
  if (!newParamKey.value.trim()) return
  params.value.push({ key: newParamKey.value.trim(), value: newParamVal.value.trim(), tip: null, isPreset: false })
  newParamKey.value = ''
  newParamVal.value = ''
  showAddParam.value = false
}

function removeParam(idx: number) { params.value.splice(idx, 1) }
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/chat')"><Icon icon="lucide:chevron-left" /></button>
      <h2>{{ langData.modelConfig_title }}</h2>
    </div>
    <div class="page-body">
      <div v-for="m in settingsStore.models" :key="m.name" class="setting-row">
        <div class="row-left">
          <Icon icon="lucide:cpu" class="row-icon" />
          <div>
            <div class="row-title">
              {{ m.name }}
              <span class="model-type-tag" :class="m.modelType === 'embedding' ? 'tag-embedding' : 'tag-llm'">
                {{ m.modelType === 'embedding' ? langData.modelConfig_typeEmbedding : langData.modelConfig_typeLlm }}
              </span>
            </div>
            <div v-if="m.baseUrl" class="row-sub">{{ m.baseUrl }}</div>
            <div v-if="m.maxContextTokens" class="row-sub ctx-info">{{ langData.modelConfig_contextLabel }}: {{ (m.maxContextTokens / 1024).toFixed(0) }}K</div>
            <div v-if="m.modelType === 'embedding' && m.dimensions" class="row-sub dim-info">{{ langData.modelConfig_dimensionsLabel }}: {{ m.dimensions }}</div>
          </div>
        </div>
        <div class="row-right">
          <button class="action-icon" @click="startEdit(m)" :title="langData.btnEdit"><Icon icon="lucide:pencil" /></button>
          <button class="action-icon danger" @click="m.id && removeModel(m.id)" :title="langData.btnDelete"><Icon icon="lucide:trash-2" /></button>
          <button v-if="(!m.modelType || m.modelType === 'llm') && settingsStore.currentModel !== m.name" class="set-btn" @click="onSetCurrent(m.name)">{{ langData.modelConfig_use }}</button>
          <span v-else class="current-badge">{{ langData.modelConfig_current }}</span>
        </div>
      </div>

      <div v-if="showForm" class="form-area">
        <div class="section-title">基本配置</div>
        <div class="config-block">
          <div class="field">
            <label class="field-label">Base URL</label>
            <input class="field-input" v-model="baseUrl" :placeholder="langData.modelConfig_baseUrl" />
          </div>
          <div class="field">
            <label class="field-label">API Key</label>
            <div class="input-row">
              <input
                class="field-input" type="password"
                :value="apiKeyDisplayValue()"
                :style="{ color: hasExistingKey && apiKeyShown !== '' ? 'var(--el-text-color-placeholder)' : 'var(--el-text-color-primary)' }"
                @input="apiKey = ($event.target as HTMLInputElement).value"
                @focus="onApiKeyFocus()"
                @blur="onApiKeyBlur()"
                :placeholder="hasExistingKey ? '' : 'sk-xxxxxxxx'"
              />
              <button v-if="hasExistingKey" class="fetch-btn" @click="clearApiKey()" :title="langData.modelConfig_clearKey">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
              </button>
            </div>
            <span v-if="hasExistingKey" class="key-hint">{{ langData.modelConfig_keySet }}</span>
          </div>
          <div class="field">
            <label class="field-label">{{ langData.modelConfig_modelName }}</label>
            <div class="input-row">
              <input class="field-input" type="text" id="modelNameInput" v-model="name" :placeholder="langData.modelConfig_modelName" @input="onModelNameInput()" />
              <select id="modelSelect" class="fetch-select" style="display:none;" @change="onModelSelect(($event.target as HTMLSelectElement).value)">
                <option value="">{{ langData.modelConfig_selectModel }}</option>
              </select>
              <button class="fetch-btn" @click="fetchModels()" :title="langData.modelConfig_fetchModels">
                <Icon v-if="!fetching" icon="lucide:search" />
                <Icon v-else icon="lucide:loader" class="spin-icon" />
              </button>
            </div>
          </div>
          <div class="field">
            <label class="field-label">{{ langData.modelConfig_maxCtx }}</label>
            <select class="field-input" v-model="maxCtxPreset">
              <option value="">{{ langData.modelConfig_ctxUnset }}</option>
              <option v-for="p in CTX_PRESETS" :key="p.value" :value="p.value">{{ p.label }} ({{ p.value }})</option>
              <option value="custom">{{ langData.modelConfig_ctxCustom }}</option>
            </select>
            <input v-if="maxCtxPreset === 'custom'" class="field-input" style="margin-top:8px" v-model="maxCtxCustom" :placeholder="langData.modelConfig_maxCtx" />
          </div>
          <div class="field">
            <label class="field-label">{{ langData.modelConfig_modelType }}</label>
            <select class="field-input" v-model="modelType">
              <option value="llm">{{ langData.modelConfig_typeLlm }}</option>
              <option value="embedding">{{ langData.modelConfig_typeEmbedding }}</option>
            </select>
          </div>
          <div v-if="modelType === 'embedding'" class="field">
            <label class="field-label">{{ langData.modelConfig_dimensionsLabel }}</label>
            <input class="field-input" type="number" v-model.number="dimensions" :placeholder="langData.modelConfig_dimensionsPlaceholder" />
          </div>
        </div>

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
                {{ thinkingOn ? langData.modelConfig_thinkingAlwaysOn : langData.modelConfig_thinkingAlwaysOff }}
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

        <div class="section-title">{{ langData.modelConfig_params }}</div>
        <div class="config-block">
          <div v-for="(p, i) in visibleParams" :key="p.key + i" class="param-row">
            <div class="param-name">
              <span :class="{ 'is-custom': !p.isPreset }">{{ p.key }}</span>
              <span v-if="p.tip" class="tip-icon" :data-tip="p.tip">?</span>
            </div>
            <input class="field-input param-value" v-model="p.value" spellcheck="false" />
            <button class="param-delete" @click="removeParam(params.findIndex(x => x.key === p.key))" :title="langData.modelConfig_deleteParam">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
            </button>
          </div>

          <div v-if="showAddParam" class="add-param-row">
            <input class="field-input param-key" v-model="newParamKey" :placeholder="langData.modelConfig_paramName" />
            <input class="field-input param-val" v-model="newParamVal" :placeholder="langData.modelConfig_paramValue" />
            <button class="add-param-confirm" @click="addCustomParam()">{{ langData.modelConfig_confirm }}</button>
            <button class="param-delete" @click="showAddParam = false; newParamKey = ''; newParamVal = ''">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
            </button>
          </div>

          <button v-if="!showAddParam" class="add-param-btn" @click="showAddParam = true">
            <Icon icon="lucide:plus" />
            {{ langData.modelConfig_addParam }}
          </button>
        </div>

        <div class="form-actions">
          <button class="btn btn-sec" @click="cancelForm">{{ langData.btnCancel }}</button>
          <button class="btn btn-pri" @click="add">{{ editId ? langData.btnSave : langData.btnAdd }}</button>
        </div>
      </div>
      <button v-else class="add-btn" @click="showForm = true; initParams()"><Icon icon="lucide:plus" />{{ langData.modelConfig_addModel }}</button>
    </div>
  </div>
</template>

<style scoped>
.settings-page { max-width: 680px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 12px; border-bottom: 1px solid var(--el-border-color); }
.row-left { display: flex; align-items: center; gap: 10px; }
.row-icon { color: var(--el-text-color-secondary); font-size: 16px; }
.row-title { font-size: 14px; color: var(--el-text-color-primary); }
.row-sub { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.ctx-info { color: var(--el-color-success); font-weight: 500; }
.dim-info { color: var(--el-color-primary); font-weight: 500; }
.model-type-tag { display: inline-block; font-size: 11px; padding: 1px 6px; border-radius: 4px; font-weight: 500; vertical-align: middle; margin-left: 6px; }
.tag-llm { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.tag-embedding { background: rgba(103,194,58,0.12); color: var(--el-color-success); }
.row-right { display: flex; align-items: center; gap: 6px; }
.action-icon { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 14px; transition: all 0.15s; }
.action-icon:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.action-icon.danger:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
.set-btn { padding: 4px 12px; border-radius: 6px; border: 1px solid var(--el-color-primary); background: transparent; color: var(--el-color-primary); cursor: pointer; font-size: 12px; font-family: inherit; }
.set-btn:hover { background: rgba(204,120,92,0.08); }
.current-badge { font-size: 12px; padding: 3px 10px; border-radius: 99px; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.add-btn { display: flex; align-items: center; gap: 6px; margin-top: 16px; padding: 8px 14px; border-radius: 8px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; width: 100%; justify-content: center; }
.add-btn:hover { background: var(--el-fill-color-light); }

.form-area { margin-top: 16px; }
.section-title { font-family: Georgia, serif; font-size: 18px; font-weight: 400; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 20px 0 12px; }
.section-title:first-child { margin-top: 0; }
.config-block { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); text-transform: uppercase; letter-spacing: 0.5px; }
.field-input { height: 40px; padding: 0 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-family: inherit; font-size: 14px; outline: none; transition: border-color 0.15s; }
.field-input:focus { border-color: var(--el-color-primary); }
.input-row { display: flex; gap: 8px; align-items: center; }
.input-row .field-input { flex: 1; }
.fetch-btn { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 16px; transition: all 0.15s; }
.fetch-btn:hover { color: var(--el-color-primary); border-color: var(--el-color-primary); }
.fetch-btn.fetching { color: var(--el-color-primary); pointer-events: none; }
.fetch-select { flex: 1; height: 40px; padding: 0 12px; border: 1px solid var(--el-color-primary); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-family: inherit; font-size: 14px; outline: none; appearance: none; padding-right: 36px; cursor: pointer; }
.key-hint { font-size: 12px; color: var(--el-color-success); }

.param-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; }
.param-row:not(:last-child) { border-bottom: 1px solid var(--el-border-color-lighter); }
.param-name { width: 150px; flex-shrink: 0; display: flex; align-items: center; gap: 6px; }
.param-name span { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); font-family: "JetBrains Mono", monospace; }
.param-name .is-custom { color: var(--el-text-color-secondary); font-style: italic; }
.tip-icon { width: 16px; height: 16px; border-radius: 50%; background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); cursor: help; display: inline-flex; align-items: center; justify-content: center; font-size: 9px; font-weight: 700; font-family: Georgia, serif; flex-shrink: 0; position: relative; }
.tip-icon:hover::after { content: attr(data-tip); position: absolute; left: 22px; top: 50%; transform: translateY(-50%); background: #181715; color: #faf9f5; padding: 6px 10px; border-radius: 6px; font-size: 12px; font-weight: 400; font-family: Inter, sans-serif; white-space: nowrap; z-index: 1000; pointer-events: none; }
.param-value { height: 32px !important; font-size: 13px !important; font-family: "JetBrains Mono", monospace !important; flex: 1; }
.param-delete { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; transition: all 0.15s; }
.param-delete:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
.add-param-btn { display: flex; align-items: center; gap: 4px; padding: 6px 12px; border-radius: 6px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; transition: all 0.15s; margin-top: 4px; }
.add-param-btn:hover { background: rgba(204,120,92,0.06); border-color: var(--el-color-primary); }
.add-param-row { display: flex; gap: 10px; align-items: center; padding: 6px 0; border-top: 1px dashed var(--el-border-color); }
.add-param-row .param-key { width: 150px; flex-shrink: 0; height: 32px; font-size: 13px; }
.add-param-row .param-val { flex: 1; height: 32px; font-size: 13px; }
.add-param-confirm { padding: 4px 12px; border-radius: 6px; border: 1px solid var(--el-color-primary); background: var(--el-color-primary); color: #fff; font-size: 12px; font-family: inherit; cursor: pointer; flex-shrink: 0; white-space: nowrap; }

.form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.btn { padding: 6px 16px; border-radius: 8px; border: none; cursor: pointer; font-size: 13px; font-family: inherit; font-weight: 500; }
.btn-sec { background: var(--el-fill-color); color: var(--el-text-color-primary); }
.btn-pri { background: var(--el-color-primary); color: #fff; }

.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }

/* 思考模式 */
.toggle-row { display: flex; align-items: center; gap: 10px; }
.toggle-hint { font-size: 13px; color: var(--el-text-color-secondary); font-style: italic; }
.toggle-switch { position: relative; display: inline-block; width: 40px; height: 22px; cursor: pointer; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 22px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 3px; bottom: 3px; background: #fff; border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(18px); }
html.dark .toggle-slider::before { background: var(--el-bg-color); }
</style>
