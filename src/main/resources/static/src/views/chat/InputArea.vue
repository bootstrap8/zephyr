<script lang="ts" setup>
import { ref, computed, nextTick, watch } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { useChatStore } from '@/store/chat'
import { useWorkspaceStore } from '@/store/workspace'
import { useConversationsStore } from '@/store/conversations'
import WorkspaceDialog from './WorkspaceDialog.vue'
import { Icon } from '@iconify/vue'
import { msg } from '@/utils/Utils'
import axios from '@/network'
import { getLangData } from '@/i18n/locale'

const emit = defineEmits<{ send: [text: string, filePaths?: string[]]; stop: [] }>()
const chatStore = useChatStore()
const inputRef = ref<HTMLDivElement>()
const settingsStore = useSettingsStore()
const showModelList = ref(false)
const showAbility = ref(false)
const showCommand = ref(false)
const hoveredAbility = ref('')
const hasInput = ref(false)
const mcpGroups = ref<{ server: string; tools: { name: string; desc: string; scope: string }[] }[]>([])
const skillList = ref<{ name: string; desc: string; scope: string }[]>([])
const mcpLoading = ref(false)
const mcpLoaded = ref(false)
const skillLoading = ref(false)
const skillLoaded = ref(false)
const mcpFilter = ref('')
const skillFilter = ref('')
const fileInputRef = ref<HTMLInputElement>()

const filteredMcpGroups = computed(() => {
  const q = mcpFilter.value.toLowerCase().trim()
  if (!q) return mcpGroups.value
  return mcpGroups.value
    .map(g => ({ server: g.server, tools: g.tools.filter(t => t.name.toLowerCase().includes(q)) }))
    .filter(g => g.tools.length > 0)
})

const filteredSkills = computed(() => {
  const q = skillFilter.value.toLowerCase().trim()
  if (!q) return skillList.value
  return skillList.value.filter(s => s.name.toLowerCase().includes(q) || s.desc.toLowerCase().includes(q))
})

const filteredSkillsShared = computed(() => filteredSkills.value.filter(s => s.scope === 'shared'))
const filteredSkillsUser = computed(() => filteredSkills.value.filter(s => s.scope !== 'shared'))

const filteredMcpGroupsShared = computed(() =>
  filteredMcpGroups.value.filter(g => g.tools[0]?.scope === 'shared'))
const filteredMcpGroupsUser = computed(() =>
  filteredMcpGroups.value.filter(g => g.tools[0]?.scope !== 'shared'))

const chatModels = computed(() => settingsStore.models.filter((m: any) => !m.modelType || m.modelType === 'llm'))
const sharedModels = computed(() => chatModels.value.filter((m: any) => m.scope === 'shared'))
const userModels = computed(() => chatModels.value.filter((m: any) => m.scope !== 'shared'))

// 键盘导航：扁平化列表 + 选中索引
interface FlatItem { name: string; desc: string }
const sharedMcpToolCount = computed(() => {
  let c = 0
  for (const g of filteredMcpGroupsShared.value) c += g.tools.length
  return c
})

const mcpFlatItems = computed<FlatItem[]>(() => {
  const items: FlatItem[] = []
  for (const g of filteredMcpGroupsShared.value) {
    for (const t of g.tools) items.push({ name: t.name, desc: t.desc })
  }
  for (const g of filteredMcpGroupsUser.value) {
    for (const t of g.tools) items.push({ name: t.name, desc: t.desc })
  }
  return items
})
const mcpActiveIdx = ref(0)
const skillActiveIdx = ref(0)

watch(mcpFilter, () => { mcpActiveIdx.value = 0 })
watch(skillFilter, () => { skillActiveIdx.value = 0 })
watch(hoveredAbility, () => { mcpActiveIdx.value = 0; skillActiveIdx.value = 0 })

function mcpFlatIdxShared(groupIdx: number, toolIdx: number): number {
  let count = 0
  for (let i = 0; i < groupIdx; i++) count += filteredMcpGroupsShared.value[i]?.tools.length || 0
  return count + toolIdx
}
function mcpFlatIdxUser(groupIdx: number, toolIdx: number): number {
  let count = sharedMcpToolCount.value
  for (let i = 0; i < groupIdx; i++) count += filteredMcpGroupsUser.value[i]?.tools.length || 0
  return count + toolIdx
}

function onMcpKeydown(e: KeyboardEvent) {
  const items = mcpFlatItems.value
  if (e.key === 'ArrowDown') { e.preventDefault(); mcpActiveIdx.value = Math.min(mcpActiveIdx.value + 1, items.length - 1); scrollActiveMcp() }
  if (e.key === 'ArrowUp') { e.preventDefault(); mcpActiveIdx.value = Math.max(mcpActiveIdx.value - 1, 0); scrollActiveMcp() }
  if (e.key === 'Enter' && items.length > 0) { e.preventDefault(); const it = items[mcpActiveIdx.value]; if (it) insertTag('mcp', it.name) }
}

function onSkillKeydown(e: KeyboardEvent) {
  const items = filteredSkills.value
  if (e.key === 'ArrowDown') { e.preventDefault(); skillActiveIdx.value = Math.min(skillActiveIdx.value + 1, items.length - 1); scrollActiveSkill() }
  if (e.key === 'ArrowUp') { e.preventDefault(); skillActiveIdx.value = Math.max(skillActiveIdx.value - 1, 0); scrollActiveSkill() }
  if (e.key === 'Enter' && items.length > 0) { e.preventDefault(); const it = items[skillActiveIdx.value]; if (it) insertTag('skill', it.name) }
}

function scrollActiveMcp() {
  nextTick(() => {
    const el = document.querySelector('.sub-dropdown .sub-option.sub-active')
    el?.scrollIntoView({ block: 'nearest' })
  })
}
function scrollActiveSkill() {
  nextTick(() => {
    const el = document.querySelector('.sub-dropdown .sub-option.sub-active')
    el?.scrollIntoView({ block: 'nearest' })
  })
}

const workspaceStore = useWorkspaceStore()
const convStore = useConversationsStore()
const showWorkspaceList = ref(false)
const showNewWorkspace = ref(false)
// 对话绑定工作空间后不可修改
const workspaceLocked = computed(() => !!convStore.currentId)
const workspaceLockedTooltip = computed(() =>
  workspaceLocked.value ? langData.inputArea_workspaceLocked : ''
)
const showKbList = ref(false)
const selectedKbIds = ref<string[]>([])

const langData = getLangData()

const modeLabel = computed(() => {
  switch (chatStore.mode) {
    case 'acceptEdits': return 'Accept Edits'
    case 'bypass': return 'Bypass'
    default: return 'Default'
  }
})

const modeTooltip = computed(() => {
  switch (chatStore.mode) {
    case 'acceptEdits': return langData.inputArea_modeAcceptEdits
    case 'bypass': return langData.inputArea_modeBypass
    default: return langData.inputArea_modeDefault
  }
})

const abilityItems = [
  { key: 'mcp', label: 'MCP' },
  { key: 'skills', label: 'Skills' },
]
const commandItems = [
  { cmd: '/context', label: langData.inputArea_contextUsage },
  { cmd: '/clear', label: langData.cmd_clearChat },
  { cmd: '/help', label: langData.cmd_viewHelp },
]

function formatContextSize(tokens?: number | string): string {
  const n = Number(tokens)
  if (!n) return ''
  if (n >= 1024 * 1024) return Math.round(n / (1024 * 1024)) + 'M'
  if (n >= 1024) return Math.round(n / 1024) + 'K'
  return String(n)
}

const THINKING_KEYS = ['thinking.type', 'thinking.budget_tokens', 'enable_thinking', 'reasoning_effort', 'thinking_budget']

function hasThinking(paramsStr?: string): boolean {
  if (!paramsStr) return false
  try {
    const obj = JSON.parse(paramsStr)
    const check = (o: any): boolean => {
      if (typeof o !== 'object' || o === null) return false
      for (const k of Object.keys(o)) {
        const fullKey = k
        if (THINKING_KEYS.some(tk => tk === fullKey || fullKey.endsWith('.' + tk.split('.').pop()!))) return true
        if (typeof o[k] === 'object' && check(o[k])) return true
      }
      return false
    }
    return check(obj)
  } catch { return false }
}

const MAX_UNDO = 50
const undoStack: string[] = []

function pushUndo() {
  const el = inputRef.value
  if (!el) return
  undoStack.push(el.innerHTML)
  if (undoStack.length > MAX_UNDO) undoStack.shift()
}

function onInput() {
  const el = inputRef.value
  if (el) {
    // 不清空 innerHTML，避免破坏 undo 历史
    if (!el.textContent || el.textContent.trim() === '') {
      hasInput.value = false
    } else {
      hasInput.value = true
    }
    return
  }
  hasInput.value = false
}

function onKeydown(e: KeyboardEvent) {
  const el = inputRef.value
  if (!el) return
  // Ctrl+Z 撤销
  if (e.key === 'z' && (e.ctrlKey || e.metaKey) && !e.shiftKey) {
    e.preventDefault()
    if (undoStack.length > 0) {
      el.innerHTML = undoStack.pop()!
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    return
  }
  // 保存当前状态用于撤销（排除功能键）
  if (!e.ctrlKey && !e.metaKey && !['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End','PageUp','PageDown','Escape','Tab','Shift','Alt','Control','Meta','CapsLock','Enter','F1','F2','F3','F4','F5','F6','F7','F8','F9','F10','F11','F12'].includes(e.key)) {
    pushUndo()
  }
  // Backspace 删除 tag：光标在文本节点开头且前一个兄弟是 tag
  if (e.key === 'Backspace') {
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      if (range.collapsed && range.startOffset === 0) {
        const prev = range.startContainer.previousSibling
        if (prev && prev.nodeType === Node.ELEMENT_NODE && (prev as Element).classList.contains('cmd-tag')) {
          e.preventDefault()
          pushUndo()
          prev.remove()
          el.dispatchEvent(new Event('input', { bubbles: true }))
          return
        }
      }
    }
  }
  // Shift+Tab: 切换模式
  if (e.key === 'Tab' && e.shiftKey) { e.preventDefault(); chatStore.cycleMode(); return }
  // Ctrl/Cmd+Enter: 发送
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSend(); return }
  // Shift+Enter: 换行（浏览器自然插入 <br>，只需保存撤销状态）
  if (e.key === 'Enter' && e.shiftKey) { pushUndo(); return }
  // 普通 Enter: 不做任何操作
  if (e.key === 'Enter') { e.preventDefault() }
}

function onPaste(e: ClipboardEvent) {
  e.preventDefault()
  pushUndo()
  const text = e.clipboardData?.getData('text/plain')
  if (!text) return
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0) {
    const range = sel.getRangeAt(0)
    range.deleteContents()
    range.insertNode(document.createTextNode(text))
    range.collapse(false)
    sel.removeAllRanges()
    sel.addRange(range)
  }
  const el = inputRef.value
  hasInput.value = !!el && (el.textContent || '').trim().length > 0
}

function doSend() {
  const el = inputRef.value
  if (!el) return

  const parts: string[] = []
  const filePaths: string[] = []
  for (const child of Array.from(el.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      parts.push(child.textContent || '')
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const elem = child as Element
      if (elem.classList.contains('cmd-tag')) {
        const type = elem.getAttribute('data-type')
        const name = elem.getAttribute('data-name')
        if (type === 'file' && name) {
          filePaths.push(name)
        } else if (type && name) {
          const prefix = type === 'mcp' ? 'MCP' : 'Skill'
          parts.push(prefix + '/' + name)
        }
      } else if (elem.tagName === 'BR') {
        parts.push('\n')
      } else if (elem.tagName === 'DIV' || elem.tagName === 'P') {
        parts.push(elem.textContent || '')
        parts.push('\n')
      } else {
        parts.push(elem.textContent || '')
      }
    }
  }

  const msg = parts.join('').replace(/ /g, ' ').trim()
  if (!msg && filePaths.length === 0) return

  emit('send', msg || '', filePaths.length > 0 ? filePaths : undefined)
  el.innerHTML = ''
  hasInput.value = false
  undoStack.length = 0
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

function toggleWorkspaceList() {
  closeAll(); showWorkspaceList.value = !showWorkspaceList.value
}

function selectWorkspace(id: string | null) {
  workspaceStore.selectWorkspace(id)
  showWorkspaceList.value = false
}

function loadKbData() {
  settingsStore.loadKnowledgeBases()
  const convId = convStore.currentId
  if (convId) {
    axios({ url: '/knowledge/conversation/kb/list', method: 'get', params: { conversationId: convId } })
      .then(res => { if (res.data.state === 'OK') selectedKbIds.value = res.data.body || [] })
      .catch(() => {})
  }
}

function toggleKb(kbId: string) {
  const convId = convStore.currentId
  if (!convId) return
  const idx = selectedKbIds.value.indexOf(kbId)
  if (idx >= 0) { selectedKbIds.value.splice(idx, 1) }
  else { selectedKbIds.value.push(kbId) }
  axios({ url: '/knowledge/conversation/kb/save', method: 'post', data: { conversationId: convId, kbIds: [...selectedKbIds.value] } })
    .catch(() => msg(langData.axiosRequestErr, 'error'))
}

function toggleKbList() {
  if (!convStore.currentId) return
  loadKbData()
  closeAll(); showKbList.value = !showKbList.value
}

const sharedKbs = computed(() => settingsStore.knowledgeBases.filter((kb: any) => kb.scope === 'shared'))
const userKbs = computed(() => settingsStore.knowledgeBases.filter((kb: any) => kb.scope !== 'shared'))

watch(() => convStore.currentId, () => {
  selectedKbIds.value = []
  loadKbData()
})

function toggleModelList() {
  if (chatModels.value.length === 0) return
  closeAll() ; showModelList.value = !showModelList.value
}

async function selectModel(name: string) {
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) { await settingsStore.setDefaultModelRemote(m.id) }
  else { settingsStore.setModel(name) }
  showModelList.value = false
}

function closeModelList() { showModelList.value = false }

async function onAbilityHover(key: string) {
  hoveredAbility.value = key
  mcpFilter.value = ''
  skillFilter.value = ''
  if (key === 'mcp') {
    if (!mcpLoaded.value) await loadMcpTools()
    await nextTick()
    ;(document.querySelector('.sub-search-input') as HTMLInputElement)?.focus()
  }
  if (key === 'skills') {
    if (!skillLoaded.value) await loadSkills()
    await nextTick()
    ;(document.querySelector('.sub-search-input') as HTMLInputElement)?.focus()
  }
}

async function loadMcpTools() {
  if (mcpLoading.value) return
  mcpLoading.value = true
  try {
    const res = await axios({ url: '/mcp/server/list', method: 'get' })
    if (res.data.state !== 'OK') return
    const servers = res.data.body
    const groups: typeof mcpGroups.value = []
    const toolReqs = servers.map((s: any) =>
      axios({ url: '/mcp/tool/list', method: 'get', params: { serverId: s.id } })
        .then(r => ({ server: s.name, tools: (r.data.state === 'OK' ? r.data.body : []).filter((t: any) => t.enabled === 1).map((t: any) => ({ name: t.toolName, desc: t.description, scope: s.scope || 'user' })) }))
        .catch(() => ({ server: s.name, tools: [] }))
    )
    const results = await Promise.all(toolReqs)
    mcpGroups.value = results.filter(g => g.tools.length > 0).sort((a, b) => {
      const sa = a.tools[0]?.scope === 'shared' ? 0 : 1
      const sb = b.tools[0]?.scope === 'shared' ? 0 : 1
      return sa - sb
    })
    mcpLoaded.value = true
  } catch (_) {}
  finally { mcpLoading.value = false }
}

async function loadSkills() {
  if (skillLoading.value) return
  skillLoading.value = true
  try {
    const res = await axios({ url: '/skill/list', method: 'get' })
    if (res.data.state === 'OK') {
      skillList.value = (res.data.body as any[]).filter((s: any) => s.enabled === 1 || s.enabled === true)
        .map((s: any) => ({ name: s.skillName || s.displayName, desc: s.description, scope: s.scope || 'user' }))
        .sort((a, b) => {
          if (a.scope === 'shared' && b.scope !== 'shared') return -1
          if (a.scope !== 'shared' && b.scope === 'shared') return 1
          return a.name.localeCompare(b.name)
        })
    }
    skillLoaded.value = true
  } catch (_) {}
  finally { skillLoading.value = false }
}

function insertCommand(cmd: string) {
  // 会话/操作命令：直接发送
  if (cmd.startsWith('/')) {
    emit('send', cmd)
    closeAll()
    return
  }
  // 兼容：其他命令作为纯文本插入
  const el = inputRef.value
  if (el) {
    el.focus()
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      range.deleteContents()
      range.insertNode(document.createTextNode(cmd + ' '))
      range.collapse(false)
      sel.removeAllRanges()
      sel.addRange(range)
    }
  }
  closeAll()
}

function insertTag(type: 'mcp' | 'skill' | 'file', name: string, displayName?: string) {
  const el = inputRef.value
  if (!el) return

  pushUndo()
  el.focus()

  const sel = window.getSelection()
  if (!sel) return

  if (sel.rangeCount === 0 || !el.contains(sel.anchorNode)) {
    const range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(false)
    sel.removeAllRanges()
    sel.addRange(range)
  }

  const prefix = type === 'mcp' ? 'MCP' : type === 'skill' ? 'Skill' : 'File'
  const display = displayName || name
  const tag = document.createElement('span')
  tag.contentEditable = 'false'
  tag.className = `cmd-tag cmd-tag--${type}`
  tag.setAttribute('data-type', type)
  tag.setAttribute('data-name', name)
  tag.innerHTML = `<span class="cmd-tag__prefix">${prefix}</span><span class="cmd-tag__sep">/</span><span class="cmd-tag__name">${display}</span>`

  const range = sel.getRangeAt(0)
  range.deleteContents()
  range.insertNode(tag)

  const space = document.createTextNode(' ')
  range.setStartAfter(tag)
  range.collapse(true)
  tag.after(space)
  range.setStartAfter(space)
  range.collapse(true)
  sel.removeAllRanges()
  sel.addRange(range)

  el.dispatchEvent(new Event('input', { bubbles: true }))
  closeAll()
}

function triggerFileInput() {
  fileInputRef.value?.click()
}

async function onFilesSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (let i = 0; i < files.length; i++) {
    const f = files[i]

    const formData = new FormData()
    formData.append('file', f)
    const wsId = workspaceStore.currentId
    if (!wsId) continue
    formData.append('workspaceId', wsId)

    try {
      const res = await axios({ url: '/chat/upload', method: 'post', data: formData })
      if (res.data.state === 'OK') {
        insertTag('file', res.data.body.path, res.data.body.name)
      }
    } catch (_) {}
  }
  input.value = ''
}

function closeAll() {
  showModelList.value = false
  showKbList.value = false
  showAbility.value = false
  showCommand.value = false
  hoveredAbility.value = ''
}

</script>

<template>
  <div class="input-section">
    <div class="input-container">
      <div
        ref="inputRef"
        class="input-textarea"
        contenteditable="true"
        @keydown="onKeydown"
        @input="onInput"
        @paste="onPaste"
        :data-placeholder="langData.inputArea_placeholder"
      ></div>
      <div class="input-toolbar">
        <div class="input-left">
          <!-- 模式切换 Pill -->
          <div class="mode-pill" :class="'mode-' + chatStore.mode" @click.stop="chatStore.cycleMode()" :title="modeTooltip">
            <span class="mode-dot"></span>
            <span class="mode-label">{{ modeLabel }}</span>
          </div>

          <!-- 工作空间选择 -->
          <div class="tool-pick" :class="{ 'ws-locked': workspaceLocked }"
               @click.stop="!workspaceLocked && toggleWorkspaceList()"
               :title="workspaceLockedTooltip">
            <template v-if="workspaceStore.current">
              <Icon icon="lucide:folder" class="ws-icon" />
              <span>{{ workspaceStore.current.name }}</span>
            </template>
            <template v-else>
              <Icon icon="lucide:folder" class="ws-icon dim" />
              <span class="ws-placeholder">{{ workspaceLocked ? langData.inputArea_noWorkspace : '' }}</span>
            </template>
            <Icon v-if="!workspaceLocked" icon="lucide:chevron-down" class="pick-arrow" />
            <Icon v-else icon="lucide:lock" class="ws-lock-icon" />
            <div v-if="showWorkspaceList && !workspaceLocked" class="pick-dropdown ws-dropdown" @click.stop>
              <div v-for="ws in workspaceStore.workspaces" :key="ws.id"
                   class="pick-option ws-option"
                   :class="{ current: workspaceStore.currentId === ws.id }"
                   @click="selectWorkspace(ws.id)">
                <span class="ws-name">{{ ws.name }}</span>
                <span class="ws-path">{{ ws.path }}</span>
              </div>
              <div v-if="workspaceStore.workspaces.length > 0" class="pick-divider"></div>
              <div class="pick-option" @click="showWorkspaceList = false; showNewWorkspace = true">
                <Icon icon="lucide:plus" />{{ langData.inputArea_newWorkspace }}
              </div>
            </div>
          </div>

          <!-- 模型切换 -->
          <div class="tool-pick" @click.stop="toggleModelList">
            <Icon icon="lucide:brain" class="pick-icon" />
            <span>{{ chatModels.length ? settingsStore.currentModel : langData.inputArea_noModel }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showModelList" class="pick-dropdown model-dropdown" @click.stop>
              <template v-if="sharedModels.length > 0">
                <div class="kb-section-label">共享模型</div>
                <div v-for="m in sharedModels" :key="m.name" class="pick-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
                  <div class="model-option-main">
                    <span class="model-name">{{ m.name }}</span>
                    <span class="model-tags">
                      <span class="skill-scope-badge scope-shared">共享</span>
                      <span v-if="hasThinking(m.params)" class="model-tag think-tag">思考</span>
                      <span v-if="m.maxContextTokens" class="model-tag ctx-tag">{{ formatContextSize(m.maxContextTokens) }}</span>
                    </span>
                  </div>
                  <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
                </div>
              </template>
              <div v-if="sharedModels.length > 0 && userModels.length > 0" class="kb-section-divider"></div>
              <template v-if="userModels.length > 0">
                <div class="kb-section-label">我的模型</div>
                <div v-for="m in userModels" :key="m.name" class="pick-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
                  <div class="model-option-main">
                    <span class="model-name">{{ m.name }}</span>
                    <span class="model-tags">
                      <span class="skill-scope-badge scope-user">个人</span>
                      <span v-if="hasThinking(m.params)" class="model-tag think-tag">思考</span>
                      <span v-if="m.maxContextTokens" class="model-tag ctx-tag">{{ formatContextSize(m.maxContextTokens) }}</span>
                    </span>
                  </div>
                  <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
                </div>
              </template>
            </div>
          </div>

          <!-- 知识库选择 -->
          <div class="tool-pick" :class="{ dim: !convStore.currentId }" @click.stop="toggleKbList()">
            <Icon icon="lucide:library" class="pick-icon" />
            <span>{{ selectedKbIds.length > 0 ? selectedKbIds.length + ' 知识库' : langData.settingsPanel_kbSelect }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showKbList" class="pick-dropdown kb-dropdown" @click.stop>
              <div v-if="settingsStore.knowledgeBases.length === 0" class="sub-loading">{{ langData.knowledgeMgmt_noKb }}</div>
              <template v-else>
                <template v-if="sharedKbs.length > 0">
                  <div class="kb-section-label">{{ langData.knowledgeMgmt_sharedTab || '共享知识库' }}</div>
                  <div v-for="kb in sharedKbs" :key="kb.id" class="pick-option kb-option"
                       :class="{ current: selectedKbIds.includes(kb.id) }"
                       @click="toggleKb(kb.id)">
                    <span class="kb-check-box" :class="{ checked: selectedKbIds.includes(kb.id) }">
                      <Icon v-if="selectedKbIds.includes(kb.id)" icon="lucide:check" class="kb-chk-icon" />
                    </span>
                    <span class="kb-opt-name" :title="kb.name">{{ kb.name }}</span>
                    <span class="skill-scope-badge scope-shared">{{ langData.knowledgeMgmt_shared || '共享' }}</span>
                    <span class="kb-opt-count">{{ kb.docCount }} 文档</span>
                  </div>
                </template>
                <div v-if="sharedKbs.length > 0 && userKbs.length > 0" class="kb-section-divider"></div>
                <template v-if="userKbs.length > 0">
                  <div class="kb-section-label">{{ langData.knowledgeMgmt_userTab || '我的知识库' }}</div>
                  <div v-for="kb in userKbs" :key="kb.id" class="pick-option kb-option"
                       :class="{ current: selectedKbIds.includes(kb.id) }"
                       @click="toggleKb(kb.id)">
                    <span class="kb-check-box" :class="{ checked: selectedKbIds.includes(kb.id) }">
                      <Icon v-if="selectedKbIds.includes(kb.id)" icon="lucide:check" class="kb-chk-icon" />
                    </span>
                    <span class="kb-opt-name" :title="kb.name">{{ kb.name }}</span>
                    <span class="skill-scope-badge scope-user">{{ langData.knowledgeMgmt_personal || '个人' }}</span>
                    <span class="kb-opt-count">{{ kb.docCount }} 文档</span>
                  </div>
                </template>
              </template>
            </div>
          </div>

          <!-- 能力（二级菜单） -->
          <div class="tool-pick" @click.stop="closeAll(); showAbility = !showAbility">
            <Icon icon="lucide:wand" class="pick-icon" />
            <span>{{ langData.inputArea_abilities }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showAbility" class="pick-dropdown ability-menu" @click.stop>
              <div v-for="it in abilityItems" :key="it.key"
                   class="pick-option ability-parent"
                   :class="{ active: hoveredAbility === it.key }"
                   @mouseenter="onAbilityHover(it.key)">
                <span>{{ it.label }}</span>
                <Icon icon="lucide:chevron-right" class="sub-arrow" />
                <!-- 二级子菜单 -->
                <div v-if="hoveredAbility === it.key" class="sub-dropdown">
                  <template v-if="it.key === 'mcp'">
                    <template v-if="mcpLoaded">
                      <div class="sub-search">
                        <input v-model="mcpFilter" class="sub-search-input" :placeholder="langData.inputArea_searchPlaceholder" @click.stop @keydown="onMcpKeydown" />
                      </div>
                      <template v-if="filteredMcpGroups.length > 0">
                        <!-- 共享 MCP -->
                        <template v-if="filteredMcpGroupsShared.length > 0">
                          <div class="kb-section-label">{{ langData.mcpMgmt_sharedTab || '共享 MCP' }}</div>
                          <template v-for="(g, gIdx) in filteredMcpGroupsShared" :key="g.server">
                            <div class="sub-group-label ability-server-label">{{ g.server }}</div>
                            <div v-for="(t, tIdx) in g.tools" :key="t.name" class="pick-option sub-option ability-tool-item" :class="{ 'sub-active': mcpFlatIdxShared(gIdx, tIdx) === mcpActiveIdx }" @click="insertTag('mcp', t.name)">
                              <span class="cmd-name">{{ t.name }}</span>
                              <span class="skill-scope-badge scope-shared">{{ langData.skillMgmt_scope_shared_badge || '共享' }}</span>
                              <span class="cmd-desc" v-if="t.desc">{{ t.desc }}</span>
                            </div>
                          </template>
                        </template>
                        <div v-if="filteredMcpGroupsShared.length > 0 && filteredMcpGroupsUser.length > 0" class="kb-section-divider"></div>
                        <!-- 我的 MCP -->
                        <template v-if="filteredMcpGroupsUser.length > 0">
                          <div class="kb-section-label">{{ langData.mcpMgmt_userTab || '我的 MCP' }}</div>
                          <template v-for="(g, gIdx) in filteredMcpGroupsUser" :key="g.server">
                            <div class="sub-group-label ability-server-label">{{ g.server }}</div>
                            <div v-for="(t, tIdx) in g.tools" :key="t.name" class="pick-option sub-option ability-tool-item" :class="{ 'sub-active': mcpFlatIdxUser(gIdx, tIdx) === mcpActiveIdx }" @click="insertTag('mcp', t.name)">
                              <span class="cmd-name">{{ t.name }}</span>
                              <span class="skill-scope-badge scope-user">{{ langData.skillMgmt_scope_user_badge || '个人' }}</span>
                              <span class="cmd-desc" v-if="t.desc">{{ t.desc }}</span>
                            </div>
                          </template>
                        </template>
                      </template>
                      <div v-else class="sub-loading">{{ mcpFilter ? langData.inputArea_noMatch : langData.inputArea_noSkills }}</div>
                    </template>
                    <div v-else-if="mcpLoading" class="sub-loading">{{ langData.inputArea_loading }}</div>
                    <div v-else class="sub-loading">{{ langData.inputArea_noSkills }}</div>
                  </template>
                  <template v-if="it.key === 'skills'">
                    <template v-if="skillLoaded">
                      <div class="sub-search">
                        <input v-model="skillFilter" class="sub-search-input" :placeholder="langData.inputArea_searchPlaceholder" @click.stop @keydown="onSkillKeydown" />
                      </div>
                      <template v-if="filteredSkills.length > 0">
                        <template v-if="filteredSkillsShared.length > 0">
                          <div class="kb-section-label">共享 Skill</div>
                          <div v-for="(s, idx) in filteredSkillsShared" :key="s.name" class="pick-option sub-option skill-item" :class="{ 'sub-active': idx === skillActiveIdx }" @click="insertTag('skill', s.name)">
                            <span class="cmd-name">{{ s.name }}</span>
                            <span class="skill-scope-badge scope-shared">{{ langData.skillMgmt_scope_shared_badge || '共享' }}</span>
                            <span class="cmd-desc" v-if="s.desc">{{ s.desc }}</span>
                          </div>
                        </template>
                        <div v-if="filteredSkillsShared.length > 0 && filteredSkillsUser.length > 0" class="kb-section-divider"></div>
                        <template v-if="filteredSkillsUser.length > 0">
                          <div class="kb-section-label">我的 Skill</div>
                          <div v-for="(s, idx) in filteredSkillsUser" :key="s.name" class="pick-option sub-option skill-item" :class="{ 'sub-active': (idx + filteredSkillsShared.length) === skillActiveIdx }" @click="insertTag('skill', s.name)">
                            <span class="cmd-name">{{ s.name }}</span>
                            <span class="skill-scope-badge scope-user">{{ langData.skillMgmt_scope_user_badge || '个人' }}</span>
                            <span class="cmd-desc" v-if="s.desc">{{ s.desc }}</span>
                          </div>
                        </template>
                      </template>
                      <div v-else class="sub-loading">{{ skillFilter ? langData.inputArea_noMatch : langData.inputArea_noSkills }}</div>
                    </template>
                    <div v-else-if="skillLoading" class="sub-loading">{{ langData.inputArea_loading }}</div>
                    <div v-else class="sub-loading">{{ langData.inputArea_noSkills }}</div>
                  </template>
                </div>
              </div>
            </div>
          </div>

          <!-- 命令 -->
          <div class="tool-pick" @click.stop="closeAll(); showCommand = !showCommand">
            <Icon icon="lucide:terminal" class="pick-icon" />
            <span>{{ langData.inputArea_command }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showCommand" class="pick-dropdown" @click.stop>
              <div v-for="it in commandItems" :key="it.cmd" class="pick-option" @click="insertCommand(it.cmd)">
                <span class="cmd-name">{{ it.cmd }}</span>
                <span class="cmd-desc">{{ it.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="input-right">
          <input ref="fileInputRef" type="file" multiple style="display:none" @change="onFilesSelected" />
          <button class="action-btn" :title="langData.inputArea_uploadTooltip" @click="triggerFileInput">
            <Icon icon="lucide:paperclip" />
          </button>
          <button
            class="send-btn"
            :class="{ stop: chatStore.streaming, 'has-text': !chatStore.streaming && hasInput }"
            @click="chatStore.streaming ? $emit('stop') : doSend()"
            :disabled="!chatStore.streaming && !hasInput"
            :title="chatStore.streaming ? langData.inputArea_stopTooltip : langData.inputArea_sendTooltip"
          >
            <Icon :icon="chatStore.streaming ? 'lucide:square' : 'lucide:arrow-up'" class="send-icon" />
          </button>
        </div>
      </div>
    </div>
    <Teleport to="body">
      <div v-if="showWorkspaceList" class="model-overlay" @click="showWorkspaceList = false"></div>
      <div v-if="showModelList" class="model-overlay" @click="closeModelList"></div>
      <div v-if="showKbList" class="model-overlay" @click="showKbList = false"></div>
      <div v-if="showAbility" class="model-overlay" @click="showAbility = false"></div>
      <div v-if="showCommand" class="model-overlay" @click="showCommand = false"></div>
    </Teleport>
    <WorkspaceDialog v-if="showNewWorkspace" @close="showNewWorkspace = false" />
  </div>
</template>

<script lang="ts">
export default { inheritAttrs: false }
</script>

<style scoped>
.input-section { padding: 0 24px 12px; }
.input-container { max-width: 820px; margin: 0 auto; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; padding: 8px 12px; transition: border-color 0.2s; }
.input-container:focus-within { border-color: var(--el-color-primary); }

.input-textarea { width: 100%; resize: none; border: none; background: transparent; color: var(--el-text-color-primary); font-family: 'Inter', -apple-system, sans-serif; font-size: 15px; padding: 6px 2px 6px 0; max-height: 160px; min-height: 40px; outline: none; line-height: 1.6; overflow-y: auto; }
.input-textarea::-webkit-scrollbar { width: 2px; }
.input-textarea::-webkit-scrollbar-thumb { background: rgba(128, 128, 128, 0.8); border-radius: 1px; }
.input-textarea::-webkit-scrollbar-track { background: transparent; }
.input-textarea[data-placeholder]:empty:before {
  content: attr(data-placeholder);
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}

.input-toolbar { display: flex; align-items: center; justify-content: space-between; padding-top: 4px; }

.input-left { display: flex; align-items: center; gap: 2px; }

/* 模式切换 Pill */
.mode-pill {
  display: flex; align-items: center; gap: 3px;
  padding: 3px 10px; border-radius: 9999px; cursor: pointer;
  font-size: 11px; font-weight: 500;
  transition: background 0.15s, color 0.15s;
  user-select: none; border: 1px solid transparent;
}
.mode-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.mode-label { white-space: nowrap; }

/* Default */
.mode-default { color: var(--el-text-color-regular); }
.mode-default .mode-dot { background: #8e8b82; }
.mode-default:hover { background: var(--el-fill-color-light); }

/* Accept Edits */
.mode-acceptEdits { background: rgba(232,165,90,0.12); color: var(--el-text-color-primary); }
.mode-acceptEdits .mode-dot { background: #e8a55a; }
.mode-acceptEdits:hover { background: rgba(232,165,90,0.2); }

/* Bypass */
.mode-bypass { background: rgba(198,69,45,0.16); color: #c64545; font-weight: 600; }
.mode-bypass .mode-dot { background: #c64545; }
.mode-bypass:hover { background: rgba(198,69,45,0.24); }

/* dark */
html.dark .mode-default { color: #a09d96; }
html.dark .mode-acceptEdits { background: rgba(232,165,90,0.14); color: #faf9f5; }
html.dark .mode-bypass { background: rgba(198,69,45,0.22); color: #e07373; }

.tool-pick {
  position: relative; display: flex; align-items: center; gap: 3px;
  padding: 3px 8px; border-radius: 6px; cursor: pointer;
  font-size: 12px; color: var(--el-text-color-secondary);
  transition: background 0.15s, color 0.15s; user-select: none;
}
.tool-pick:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.pick-arrow { font-size: 10px; opacity: 0.5; }

.pick-dropdown {
  position: absolute; bottom: calc(100% + 8px); left: 0;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color);
  border-radius: 10px; box-shadow: 0 8px 32px rgba(0,0,0,0.1);
  min-width: 200px; padding: 4px; z-index: 100;
}
.model-dropdown { min-width: 320px; }
.ws-dropdown { min-width: 260px; }
.pick-option {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 6px; cursor: pointer;
  font-size: 13px; color: var(--el-text-color-primary);
  transition: background 0.1s;
}
.pick-option:hover { background: var(--el-fill-color-light); }
.pick-option.current { color: var(--el-color-primary); }
.model-option-main { display: flex; align-items: center; justify-content: space-between; flex: 1; min-width: 0; }
.model-name { flex-shrink: 0; }
.model-tags { display: flex; align-items: center; gap: 4px; margin-left: 8px; flex-shrink: 0; }
.model-tag {
  font-size: 10px; padding: 1px 5px; border-radius: 4px; line-height: 1.5;
  white-space: nowrap;
}
.ctx-tag { background: var(--el-fill-color-light); color: var(--el-text-color-secondary); }
.think-tag { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }

html.dark .ctx-tag { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
html.dark .think-tag { background: var(--el-color-primary-light-3); color: var(--el-color-primary); }
.check-icon { font-size: 15px; color: var(--el-color-primary); flex-shrink: 0; }
.cmd-name { font-weight: 600; color: var(--el-color-primary); min-width: 60px; font-size: 12px; }
.cmd-desc { color: var(--el-text-color-secondary); font-size: 12px; }

/* 能力二级菜单 */
.ability-menu { min-width: 180px; }
.ability-menu::-webkit-scrollbar { width: 1px; }
.ability-menu::-webkit-scrollbar-thumb { background: transparent; }
.ability-parent { justify-content: space-between; position: relative; }
.ability-parent.active { background: var(--el-fill-color-light); color: var(--el-color-primary); }
.sub-arrow { font-size: 12px; color: var(--el-text-color-placeholder); flex-shrink: 0; }

.sub-dropdown {
  position: absolute; left: 100%; bottom: 0;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color);
  border-radius: 10px; box-shadow: 0 8px 32px rgba(0,0,0,0.1);
  min-width: 240px; max-height: 320px; overflow-y: auto; overflow-x: hidden; padding: 4px; z-index: 110;
}
.sub-dropdown::-webkit-scrollbar { width: 1px; }
.sub-dropdown::-webkit-scrollbar-thumb { background: transparent; }
.sub-group-label {
  font-size: 11px; color: var(--el-text-color-placeholder);
  padding: 6px 10px 2px; text-transform: uppercase; letter-spacing: 0.3px;
  white-space: nowrap;
}
.sub-option {
  max-width: 400px;
}
/* 分栏标题颜色区分 */
.kb-section-label { color: var(--el-color-primary); font-weight: 600; }
/* MCP/Skill 一二缩进 */
.ability-server-label { padding-left: 20px; }
.ability-tool-item { padding-left: 32px; }
.skill-item { padding-left: 20px; }
.sub-option.sub-active { background: var(--el-fill-color-light); }
.sub-option .cmd-name { min-width: auto; font-size: 13px; font-weight: 500; white-space: nowrap; flex-shrink: 0; }
.sub-option .skill-scope-badge {
  font-size: 10px; padding: 1px 6px; border-radius: 99px;
  flex-shrink: 0;
}
.sub-option .skill-scope-badge.scope-shared {
  background: rgba(204,120,92,0.12); color: var(--el-color-primary);
}
.sub-option .skill-scope-badge.scope-user {
  background: var(--el-fill-color); color: var(--el-text-color-secondary);
}
.sub-option .cmd-desc {
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 280px;
}
.sub-loading { padding: 20px; text-align: center; font-size: 12px; color: var(--el-text-color-placeholder); }

.sub-search { padding: 6px 8px 2px; }
.sub-search-input {
  width: 100%; box-sizing: border-box; padding: 6px 10px;
  border: 1px solid var(--el-border-color); border-radius: 6px;
  background: var(--el-fill-color-light); color: var(--el-text-color-primary);
  font-size: 12px; outline: none; font-family: inherit;
  transition: border-color 0.15s;
}
.sub-search-input:focus { border-color: var(--el-color-primary); }
.sub-search-input::placeholder { color: var(--el-text-color-placeholder); }

.model-overlay { position: fixed; inset: 0; z-index: 99; }

.input-right { display: flex; align-items: center; gap: 4px; }
.action-btn { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; transition: all 0.15s; }
.action-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.send-btn { width: 32px; height: 32px; border-radius: 50%; border: none; background: var(--el-fill-color); color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; transition: all 0.15s; }
.send-btn.has-text { background: var(--el-color-primary); color: #fff; }
.send-btn.has-text:hover { background: var(--el-color-primary-dark-2); }
.send-btn:disabled { cursor: default; }
.send-btn.stop { background: var(--el-color-danger) !important; box-shadow: 0 0 0 0 rgba(198,69,69,0.4); animation: stopPulse 1.5s ease-in-out infinite; }
@keyframes stopPulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(198,69,69,0.4); } 50% { box-shadow: 0 0 0 8px rgba(198,69,69,0); } }
.send-btn.stop .send-icon { color: #fff; font-size: 12px; }

.ws-icon { font-size: 14px; color: var(--el-text-color-secondary); }
.ws-icon.dim { color: var(--el-text-color-placeholder); }
.ws-locked { opacity: 0.75; cursor: default; }
.ws-placeholder { color: var(--el-text-color-placeholder); font-size: 12px; }
.ws-lock-icon { font-size: 12px; color: var(--el-text-color-placeholder); margin-left: 4px; flex-shrink: 0; }
.pick-icon { font-size: 14px; color: var(--el-text-color-secondary); }
.ws-option { flex-direction: column; align-items: flex-start !important; gap: 2px !important; }
.ws-name { font-weight: 500; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.ws-path { color: var(--el-text-color-placeholder); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.pick-divider { height: 1px; background: var(--el-border-color); margin: 4px 0; }

/* KB selector */
.tool-pick.dim { opacity: 0.5; }
.kb-option { display: flex; align-items: center; gap: 8px !important; }
.kb-check-box { width: 16px; height: 16px; border-radius: 4px; border: 1.5px solid var(--el-border-color); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kb-check-box.checked { background: var(--el-color-primary); border-color: var(--el-color-primary); }
.kb-chk-icon { color: #fff; font-size: 11px; }
.kb-opt-name { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kb-opt-count { font-size: 11px; color: var(--el-text-color-placeholder); flex-shrink: 0; }
.kb-section-label { font-size: 11px; color: var(--el-text-color-placeholder); padding: 6px 10px 2px; text-transform: uppercase; letter-spacing: 0.3px; white-space: nowrap; }
.kb-section-divider { height: 1px; background: var(--el-border-color); margin: 4px 0; }
.kb-dropdown { width: 340px; }
</style>

<!-- 非 scoped：动态创建的 tag 元素需要全局样式 -->
<style>
.cmd-tag {
  display: inline-block;
  vertical-align: middle;
  background: #efe9de;
  border: 1px solid #e6dfd8;
  border-radius: 6px;
  padding: 1px 6px;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: default;
  user-select: none;
  margin: 0 1px;
  line-height: 1.6;
}
.cmd-tag__prefix { font-weight: 600; }
.cmd-tag--mcp .cmd-tag__prefix { color: #5db8a6; }
.cmd-tag--skill .cmd-tag__prefix { color: #e8a55a; }
.cmd-tag--file .cmd-tag__prefix { color: #6b8cce; }
.cmd-tag__sep { color: #8e8b82; margin: 0 1px; }
.cmd-tag__name { color: #141413; }

html.dark .cmd-tag {
  background-color: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
html.dark .cmd-tag__name { color: var(--el-text-color-primary); }
</style>
