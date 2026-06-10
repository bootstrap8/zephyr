<script lang="ts" setup>
import { ref } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { useChatStore } from '@/store/chat'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const emit = defineEmits<{ send: [text: string]; stop: [] }>()
const chatStore = useChatStore()
const inputRef = ref<HTMLDivElement>()
const settingsStore = useSettingsStore()
const showModelList = ref(false)
const showAbility = ref(false)
const showSession = ref(false)
const showAction = ref(false)
const hoveredAbility = ref('')
const mcpGroups = ref<{ server: string; tools: { name: string; desc: string }[] }[]>([])
const skillList = ref<{ name: string; desc: string }[]>([])

const abilityItems = [
  { key: 'mcp', label: 'MCP' },
  { key: 'skills', label: 'Skills' },
]
const sessionItems = [
  { cmd: '/resume', label: '恢复之前的对话' },
  { cmd: '/context', label: '上下文占比' },
]
const actionItems = [
  { cmd: '/clear', label: '清空当前对话' },
  { cmd: '/help', label: '查看帮助' },
]

function onInput() {
  const el = inputRef.value
  if (el) {
    if (el.innerText.trim() === '') {
      el.innerHTML = ''
    }
  }
}

function onKeydown(e: KeyboardEvent) {
  const el = inputRef.value
  if (!el) return
  // Backspace 删除 tag：光标在文本节点开头且前一个兄弟是 tag
  if (e.key === 'Backspace') {
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      if (range.collapsed && range.startOffset === 0) {
        const prev = range.startContainer.previousSibling
        if (prev && prev.nodeType === Node.ELEMENT_NODE && (prev as Element).classList.contains('cmd-tag')) {
          e.preventDefault()
          prev.remove()
          el.dispatchEvent(new Event('input', { bubbles: true }))
          return
        }
      }
    }
  }
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSend() }
}

function onPaste(e: ClipboardEvent) {
  e.preventDefault()
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
}

function doSend() {
  const el = inputRef.value
  if (!el) return

  const parts: string[] = []
  for (const child of Array.from(el.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      parts.push(child.textContent || '')
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const elem = child as Element
      if (elem.classList.contains('cmd-tag')) {
        const type = elem.getAttribute('data-type')
        const name = elem.getAttribute('data-name')
        if (type && name) {
          const prefix = type === 'mcp' ? 'MCP' : 'Skill'
          parts.push(prefix + '/' + name)
        }
      } else if (elem.tagName === 'BR') {
        parts.push('\n')
      } else {
        parts.push(elem.textContent || '')
      }
    }
  }

  const msg = parts.join('').replace(/ /g, ' ').trim()
  if (!msg) return

  emit('send', msg)
  el.innerHTML = ''
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

function toggleModelList() {
  if (settingsStore.models.length === 0) return
  closeAll() ; showModelList.value = !showModelList.value
}

async function selectModel(name: string) {
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) { await settingsStore.setDefaultModelRemote(m.id) }
  else { settingsStore.setModel(name) }
  showModelList.value = false
}

function closeModelList() { showModelList.value = false }

function onAbilityHover(key: string) {
  hoveredAbility.value = key
  if (key === 'mcp' && mcpGroups.value.length === 0) loadMcpTools()
  if (key === 'skills' && skillList.value.length === 0) loadSkills()
}

async function loadMcpTools() {
  try {
    const res = await axios({ url: '/mcp/server/list', method: 'get' })
    if (res.data.state !== 'OK') return
    const servers = res.data.body
    const groups: typeof mcpGroups.value = []
    const toolReqs = servers.map((s: any) =>
      axios({ url: '/mcp/tool/list', method: 'get', params: { serverId: s.id } })
        .then(r => ({ server: s.name, tools: (r.data.state === 'OK' ? r.data.body : []).filter((t: any) => t.enabled === 1).map((t: any) => ({ name: t.toolName, desc: t.description })) }))
        .catch(() => ({ server: s.name, tools: [] }))
    )
    const results = await Promise.all(toolReqs)
    mcpGroups.value = results.filter(g => g.tools.length > 0)
  } catch (_) {}
}

async function loadSkills() {
  try {
    const res = await axios({ url: '/skill/list', method: 'get' })
    if (res.data.state === 'OK') {
      skillList.value = (res.data.body as any[]).filter((s: any) => s.enabled === 1 || s.enabled === true)
        .map((s: any) => ({ name: s.skillName || s.displayName, desc: s.description }))
    }
  } catch (_) {}
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

function insertTag(type: 'mcp' | 'skill', name: string) {
  const el = inputRef.value
  if (!el) return

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

  const prefix = type === 'mcp' ? 'MCP' : 'Skill'
  const tag = document.createElement('span')
  tag.contentEditable = 'false'
  tag.className = `cmd-tag cmd-tag--${type}`
  tag.setAttribute('data-type', type)
  tag.setAttribute('data-name', name)
  tag.innerHTML = `<span class="cmd-tag__prefix">${prefix}</span><span class="cmd-tag__sep">/</span><span class="cmd-tag__name">${name}</span>`

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

function closeAll() {
  showModelList.value = false
  showAbility.value = false
  showSession.value = false
  showAction.value = false
  hoveredAbility.value = ''
}

function hasContent() {
  const el = inputRef.value
  if (!el) return false
  return (el.textContent || '').trim().length > 0
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
        data-placeholder="Ctrl+Enter 发送 · Enter 换行"
      ></div>
      <div class="input-toolbar">
        <div class="input-left">
          <!-- 模型切换 -->
          <div class="tool-pick" @click.stop="toggleModelList">
            <span>{{ settingsStore.models.length ? settingsStore.currentModel : '无' }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showModelList" class="pick-dropdown" @click.stop>
              <div v-for="m in settingsStore.models" :key="m.name" class="pick-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
                <span>{{ m.name }}</span>
                <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
              </div>
            </div>
          </div>

          <!-- 能力（二级菜单） -->
          <div class="tool-pick" @click.stop="closeAll(); showAbility = !showAbility">
            <span>能力</span>
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
                    <template v-if="mcpGroups.length > 0">
                      <template v-for="g in mcpGroups" :key="g.server">
                        <div class="sub-group-label">{{ g.server }}</div>
                        <div v-for="t in g.tools" :key="t.name" class="pick-option sub-option" @click="insertTag('mcp', t.name)">
                          <span class="cmd-name">{{ t.name }}</span>
                          <span class="cmd-desc" v-if="t.desc">{{ t.desc }}</span>
                        </div>
                      </template>
                    </template>
                    <div v-else class="sub-loading">加载中...</div>
                  </template>
                  <template v-if="it.key === 'skills'">
                    <template v-if="skillList.length > 0">
                      <div v-for="s in skillList" :key="s.name" class="pick-option sub-option" @click="insertTag('skill', s.name)">
                        <span class="cmd-name">{{ s.name }}</span>
                        <span class="cmd-desc" v-if="s.desc">{{ s.desc }}</span>
                      </div>
                    </template>
                    <div v-else class="sub-loading">暂无技能</div>
                  </template>
                </div>
              </div>
            </div>
          </div>

          <!-- 会话 -->
          <div class="tool-pick" @click.stop="closeAll(); showSession = !showSession">
            <span>会话</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showSession" class="pick-dropdown" @click.stop>
              <div v-for="it in sessionItems" :key="it.cmd" class="pick-option" @click="insertCommand(it.cmd)">
                <span class="cmd-name">{{ it.cmd }}</span>
                <span class="cmd-desc">{{ it.label }}</span>
              </div>
            </div>
          </div>

          <!-- 操作 -->
          <div class="tool-pick" @click.stop="closeAll(); showAction = !showAction">
            <span>操作</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showAction" class="pick-dropdown" @click.stop>
              <div v-for="it in actionItems" :key="it.cmd" class="pick-option" @click="insertCommand(it.cmd)">
                <span class="cmd-name">{{ it.cmd }}</span>
                <span class="cmd-desc">{{ it.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="input-right">
          <button class="action-btn" title="上传附件">
            <Icon icon="lucide:paperclip" />
          </button>
          <button
            class="send-btn"
            :class="{ stop: chatStore.streaming, 'has-text': !chatStore.streaming && hasContent() }"
            @click="chatStore.streaming ? $emit('stop') : doSend()"
            :disabled="!chatStore.streaming && !hasContent()"
            :title="chatStore.streaming ? '停止输出' : '发送'"
          >
            <Icon :icon="chatStore.streaming ? 'lucide:square' : 'lucide:arrow-up'" class="send-icon" />
          </button>
        </div>
      </div>
    </div>
    <Teleport to="body">
      <div v-if="showModelList" class="model-overlay" @click="closeModelList"></div>
      <div v-if="showAbility" class="model-overlay" @click="showAbility = false"></div>
      <div v-if="showSession" class="model-overlay" @click="showSession = false"></div>
      <div v-if="showAction" class="model-overlay" @click="showAction = false"></div>
    </Teleport>
  </div>
</template>

<script lang="ts">
export default { inheritAttrs: false }
</script>

<style scoped>
.input-section { padding: 0 24px 12px; }
.input-container { max-width: 820px; margin: 0 auto; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; padding: 8px 12px; transition: border-color 0.2s; }
.input-container:focus-within { border-color: var(--el-color-primary); }

.input-textarea { width: 100%; resize: none; border: none; background: transparent; color: var(--el-text-color-primary); font-family: 'Inter', -apple-system, sans-serif; font-size: 15px; padding: 6px 0; max-height: 160px; min-height: 40px; outline: none; line-height: 1.6; }
.input-textarea[data-placeholder]:empty:before {
  content: attr(data-placeholder);
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}

.input-toolbar { display: flex; align-items: center; justify-content: space-between; padding-top: 4px; }

.input-left { display: flex; align-items: center; gap: 2px; }

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
.pick-option {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 6px; cursor: pointer;
  font-size: 13px; color: var(--el-text-color-primary);
  transition: background 0.1s;
}
.pick-option:hover { background: var(--el-fill-color-light); }
.pick-option.current { color: var(--el-color-primary); }
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
.sub-option .cmd-name { min-width: auto; font-size: 13px; font-weight: 500; white-space: nowrap; flex-shrink: 0; }
.sub-option .cmd-desc {
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 280px;
}
.sub-loading { padding: 20px; text-align: center; font-size: 12px; color: var(--el-text-color-placeholder); }

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
.cmd-tag__sep { color: #8e8b82; margin: 0 1px; }
.cmd-tag__name { color: #141413; }

html.dark .cmd-tag {
  background-color: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
html.dark .cmd-tag__name { color: var(--el-text-color-primary); }
</style>
