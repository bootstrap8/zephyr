<script lang="ts" setup>
import { onMounted, ref, watch, computed, nextTick } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { useChatStore } from '@/store/chat'
import { useSettingsStore } from '@/store/settings'
import { useWorkspaceStore } from '@/store/workspace'
import ChatSidebar from './ChatSidebar.vue'
import ChatArea from './ChatArea.vue'
import InputArea from './InputArea.vue'
import StatusBar from './StatusBar.vue'
import CommandPalette from './CommandPalette.vue'
import SettingsPanel from './SettingsPanel.vue'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'
import axios from '@/network'

const convStore = useConversationsStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const workspaceStore = useWorkspaceStore()
const showSettings = ref(false)
const langData = getLangData()

// 搜索弹窗
const showSearchDialog = ref(false)
const searchQuery = ref('')
const searchActiveIdx = ref(0)
const searchInputRef = ref<HTMLInputElement>()

const filteredConversations = computed(() => {
  const q = searchQuery.value.toLowerCase().trim()
  if (!q) return convStore.conversations
  return convStore.conversations.filter(c => c.title.toLowerCase().includes(q))
})

watch(searchQuery, () => { searchActiveIdx.value = 0 })

function openSearch() {
  searchQuery.value = ''
  searchActiveIdx.value = 0
  showSearchDialog.value = true
  nextTick(() => searchInputRef.value?.focus())
}

function closeSearch() {
  showSearchDialog.value = false
  searchQuery.value = ''
}

function onSearchKeydown(e: KeyboardEvent) {
  const items = filteredConversations.value
  if (e.key === 'ArrowDown') { e.preventDefault(); searchActiveIdx.value = Math.min(searchActiveIdx.value + 1, items.length - 1) }
  if (e.key === 'ArrowUp') { e.preventDefault(); searchActiveIdx.value = Math.max(searchActiveIdx.value - 1, 0) }
  if (e.key === 'Enter' && items.length > 0) { e.preventDefault(); const c = items[searchActiveIdx.value]; if (c) { convStore.selectConversation(c.id); closeSearch() } }
  if (e.key === 'Escape') { closeSearch() }
}

function selectSearchResult(id: string) {
  convStore.selectConversation(id)
  closeSearch()
}

function formatSearchTime(ts: number) {
  const d = new Date(ts * 1000)
  const now = new Date()
  const diff = now.getTime() / 1000 - ts
  if (diff < 86400) return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  if (diff < 7 * 86400) return langData.chatArea_timeDaysAgo.replace('{n}', String(Math.floor(diff / 86400)))
  return d.toLocaleDateString(undefined, { month: '2-digit', day: '2-digit' })
}

let abortController: AbortController | null = null
let msgIdCounter = 0
function nextMsgId() { return 'm' + (++msgIdCounter) }

function refreshConversationList() {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
}

function newChat() {
  chatStore.clearMessages()
  convStore.currentId = null
  settingsStore.contextUsed = 0
  chatStore.mode = 'default'
}

function onSend(text: string, filePaths?: string[]) {
  if (text === '/context') {
    axios({ url: '/chat/context-usage', method: 'get', params: { conversationId: convStore.currentId } })
      .then(res => {
        if (res.data.state === 'OK') {
          const info = res.data.body
          chatStore.addMessage({ id: nextMsgId(), role: 'system', content: langData.context_usageInfo + JSON.stringify(info, null, 2), timestamp: Date.now() / 1000 })
        }
      })
    return
  }
  if (text === '/help') {
    chatStore.addMessage({ id: nextMsgId(), role: 'system', content: langData.context_helpTitle, timestamp: Date.now() / 1000 })
    return
  }

  chatStore.startSession()
  if (abortController) abortController.abort()
  abortController = new AbortController()

  const displayText = filePaths && filePaths.length > 0
    ? langData.chatArea_filesUploaded.replace('{n}', String(filePaths.length)) + '\n' + text
    : text
  chatStore.addMessage({ id: nextMsgId(), role: 'user', content: displayText, timestamp: Date.now() / 1000 })
  chatStore.addMessage({ id: nextMsgId(), role: 'assistant', content: '', timestamp: Date.now() / 1000 })
  chatStore.streaming = true

  let lastPos = 0
  axios({
    url: `/chat/send`,
    method: 'post',
    data: { conversationId: convStore.currentId, message: text, workspaceId: workspaceStore.currentId, mode: chatStore.mode, filePaths: filePaths || [] },
    responseType: 'text',
    signal: abortController.signal,
    onDownloadProgress(evt: any) {
      const raw = evt.event?.target?.responseText || evt.currentTarget?.responseText || ''
      const newData = raw.substring(lastPos)
      lastPos = raw.length
      const lines = newData.split('\n')
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        try {
          const event = JSON.parse(line.substring(5).trim())
          if (event.type === 'token') {
            chatStore.appendToken(event.content)
          } else if (event.type === 'thinking') {
            chatStore.updateLastThinking(event.content)
          } else if (event.type === 'tool_call') {
            chatStore.upsertToolCall(event.toolName, { status: 'running' })
          } else if (event.type === 'tool_result') {
            const isError = event.toolOutput && event.toolOutput.startsWith(langData.chatArea_toolExecutionError)
            chatStore.upsertToolCall(event.toolName, {
              status: isError ? 'error' : 'success',
              output: event.toolOutput,
            })
          } else if (event.type === 'artifact') {
            if (event.artifactName && event.artifactPath) {
              chatStore.addArtifact({
                name: event.artifactName,
                path: event.artifactPath,
                contentType: event.artifactType || 'application/octet-stream',
                size: event.artifactSize || 0
              })
            }
          } else if (event.type === 'meta') {
            convStore.currentId = event.content
            refreshConversationList()
          } else if (event.type === 'clear') {
            const oldCid = convStore.currentId
            chatStore.clearMessages()
            convStore.currentId = null
            if (oldCid) convStore.removeConversation(oldCid)
            // 不在这里设 streaming = false，让 done 事件处理
            // 否则 refreshConversationList 中的 setConversations 会触发
            // currentId watcher → restoreConversation 加载其他对话的消息
          } else if (event.type === 'done') {
            chatStore.pruneEmptyAssistant()
            refreshConversationList()
            settingsStore.loadContextUsage(convStore.currentId)
            chatStore.streaming = false
          } else if (event.type === 'error') {
            chatStore.appendToken('\n\n' + langData.context_errorPrefix + (event.content || langData.context_requestFailed))
            chatStore.streaming = false
          }
        } catch (_) {}
      }
    }
  }).catch((err: any) => {
    if (err?.code !== 'ERR_CANCELED' && err?.name !== 'AbortError' && err?.name !== 'CanceledError') {
      chatStore.appendToken('\n\n' + langData.context_requestFailed)
    }
    chatStore.streaming = false
  }).then(() => {
    if (chatStore.streaming) {
      chatStore.pruneEmptyAssistant()
      chatStore.streaming = false
    }
  })
}

function onStop() {
  if (abortController) { abortController.abort(); abortController = null }
  axios({ url: '/chat/cancel', method: 'post' }).catch(() => {})
  chatStore.streaming = false
}

function restoreConversation(id: string) {
  axios({ url: `/conversations/${id}/messages`, method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const body = res.data.body
        const msgs = (body.messages || body || []).map((m: any) => ({
          id: m.id,
          role: m.role,
          content: m.content || '',
          thinking: m.thinking || '',
          toolCalls: m.toolCalls || [],
          timestamp: m.timestamp
        }))
        // 合并连续 assistant 消息的 thinking 和 content 到最后一条
        // 工具调用循环每轮产生一个 assistant 消息，但流式期间前端只用了一个 assistant 气泡
        const merged: any[] = []
        for (let i = 0; i < msgs.length; i++) {
          if (msgs[i].role === 'assistant') {
            const group: any[] = []
            while (i < msgs.length && msgs[i].role === 'assistant') {
              group.push(msgs[i])
              i++
            }
            i--
            const thinkingParts: string[] = []
            const contentParts: string[] = []
            const toolCallsParts: any[] = []
            for (const m of group) {
              if (m.thinking) thinkingParts.push(m.thinking)
              if (m.content) contentParts.push(m.content)
              if (m.toolCalls && m.toolCalls.length > 0) toolCallsParts.push(...m.toolCalls)
            }
            const last = group[group.length - 1]
            last.thinking = thinkingParts.join('\n')
            last.content = contentParts.join('\n')
            if (toolCallsParts.length > 0) {
              // 去重：同一工具多轮调用，只保留最后一次
              const seen = new Set<string>()
              const deduped: any[] = []
              for (let j = toolCallsParts.length - 1; j >= 0; j--) {
                if (!seen.has(toolCallsParts[j].name)) {
                  seen.add(toolCallsParts[j].name)
                  deduped.unshift(toolCallsParts[j])
                }
              }
              last.toolCalls = deduped
            }
            merged.push(last)
          } else {
            merged.push(msgs[i])
          }
        }
        msgs.length = 0
        msgs.push(...merged)
        chatStore.clearMessages()
        if (body.workspaceId) {
          workspaceStore.selectWorkspace(body.workspaceId)
        }
        // 为 assistant 消息重建 artifacts（从关联的 tool 消息内容中解析路径）
        for (let i = 1; i < msgs.length; i++) {
          if (msgs[i].role === 'assistant' && msgs[i].toolCalls && msgs[i].toolCalls.length > 0) {
            const artifacts: { name: string; path: string; contentType: string; size: number }[] = []
            // 从前面的 tool 消息中查找产物文件路径
            for (let j = i - 1; j >= 0 && msgs[j].role === 'tool'; j--) {
              const content = msgs[j].content || ''
              // 匹配 Write 工具输出中的文件路径模式
              const artifactRe = /(?:output|saved to|写入|created?|生成)[:\s]+[`"']?([^\s`"']+\.(?:html|css|js|json|png|jpg|jpeg|gif|svg|webp|pdf))[`"']?/gi
              let match: RegExpExecArray | null
              artifactRe.lastIndex = 0
              while ((match = artifactRe.exec(content)) !== null) {
                const filePath = match[1]
                const fileName = filePath.split('/').pop() || filePath
                const ext = fileName.substring(fileName.lastIndexOf('.'))
                const mimeMap: Record<string, string> = {
                  '.html': 'text/html', '.htm': 'text/html',
                  '.css': 'text/css', '.js': 'application/javascript', '.json': 'application/json',
                  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
                  '.gif': 'image/gif', '.svg': 'image/svg+xml', '.webp': 'image/webp',
                  '.pdf': 'application/pdf'
                }
                artifacts.push({
                  name: fileName,
                  path: filePath,
                  contentType: mimeMap[ext] || 'application/octet-stream',
                  size: 0
                })
              }
            }
            if (artifacts.length > 0) {
              msgs[i].artifacts = artifacts
            }
          }
        }
        msgs.forEach((m: any) => chatStore.addMessage(m))
      }
    })
}

watch(() => convStore.currentId, (newId) => {
  if (newId && !chatStore.streaming) {
    restoreConversation(newId)
  } else if (!newId) {
    chatStore.clearMessages()
  }
})

onMounted(() => {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
  settingsStore.loadModels()
  settingsStore.loadMcpServers()
  axios({ url: '/workspace/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') workspaceStore.setWorkspaces(res.data.body)
    })
})
</script>

<template>
  <div class="app-layout">
    <ChatSidebar @open-settings="showSettings = true" @new-chat="newChat" />
    <div class="main-area">
      <div class="top-toolbar" :class="{ show: convStore.sidebarCollapsed }">
        <button class="tb-btn" @click="convStore.toggleSidebar()" :title="langData.chatSidebar_expandTooltip">
          <Icon icon="lucide:panel-left-open" />
        </button>
        <span class="tb-logo" @click="convStore.toggleSidebar()">zephyr</span>
        <span class="tb-divider"></span>
        <button class="tb-btn" :title="langData.chatSidebar_newChat" @click="newChat">
          <Icon icon="lucide:plus-circle" />
        </button>
        <button class="tb-btn" :title="langData.chatSidebar_searchConv" @click="openSearch">
          <Icon icon="lucide:search" />
        </button>
      </div>
      <!-- 搜索弹窗 -->
      <Teleport to="body">
        <div v-if="showSearchDialog" class="search-overlay" @click="closeSearch"></div>
        <div v-if="showSearchDialog" class="search-dialog">
          <div class="search-input-wrap">
            <Icon icon="lucide:search" class="search-input-icon" />
            <input ref="searchInputRef" v-model="searchQuery" class="search-input" :placeholder="langData.chatSidebar_searchPlaceholder" @keydown="onSearchKeydown" />
          </div>
          <div v-if="filteredConversations.length > 0" class="search-results">
            <div v-for="(c, idx) in filteredConversations" :key="c.id"
                 class="search-item"
                 :class="{ active: idx === searchActiveIdx }"
                 @click="selectSearchResult(c.id)"
                 @mouseenter="searchActiveIdx = idx">
              <Icon icon="lucide:message-square" class="search-item-icon" />
              <span class="search-item-title">{{ c.title }}</span>
              <span class="search-item-time">{{ formatSearchTime(c.updatedAt) }}</span>
            </div>
          </div>
          <div v-else class="search-empty">{{ searchQuery ? langData.chatSidebar_noMatch : langData.chatSidebar_noConversations }}</div>
        </div>
      </Teleport>

      <ChatArea />
      <CommandPalette />
      <InputArea @send="onSend" @stop="onStop" />
      <StatusBar />
    </div>
    <SettingsPanel :visible="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped>
.app-layout { display: flex; height: 100vh; }
.main-area { flex: 1; display: flex; flex-direction: column; min-width: 0; position: relative; background: var(--el-bg-color); }

.top-toolbar { display: none; align-items: center; gap: 4px; position: absolute; top: 0; left: 0; z-index: 30; padding: 10px 16px; }
.top-toolbar.show { display: flex; }
.tb-btn { width: 34px; height: 34px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; transition: background 0.15s, color 0.15s; }
.tb-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.tb-logo { font-family: Georgia, 'Times New Roman', serif; font-size: 18px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin-right: 10px; cursor: pointer; white-space: nowrap; }
.tb-divider { width: 14px; height: 1px; background: var(--el-border-color); margin: 0 6px; transform: rotate(90deg); flex-shrink: 0; }

/* 搜索弹窗 */
.search-overlay { position: fixed; inset: 0; z-index: 1000; background: rgba(0,0,0,0.2); backdrop-filter: blur(2px); }
.search-dialog { position: fixed; top: 20%; left: 50%; transform: translateX(-50%); width: 480px; max-width: 90vw; max-height: 60vh; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; box-shadow: 0 12px 48px rgba(0,0,0,0.12); z-index: 1001; display: flex; flex-direction: column; overflow: hidden; }

.search-input-wrap { display: flex; align-items: center; gap: 8px; padding: 14px 16px; border-bottom: 1px solid var(--el-border-color); }
.search-input-icon { color: var(--el-text-color-placeholder); font-size: 18px; flex-shrink: 0; }
.search-input { flex: 1; border: none; background: transparent; color: var(--el-text-color-primary); font-size: 15px; outline: none; font-family: inherit; }
.search-input::placeholder { color: var(--el-text-color-placeholder); }

.search-results { flex: 1; overflow-y: auto; padding: 8px; }
.search-results::-webkit-scrollbar { width: 1px; }
.search-results::-webkit-scrollbar-thumb { background: transparent; }
.search-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; cursor: pointer; transition: background 0.1s; }
.search-item:hover, .search-item.active { background: var(--el-fill-color-light); }
.search-item-icon { color: var(--el-text-color-placeholder); font-size: 16px; flex-shrink: 0; }
.search-item-title { flex: 1; font-size: 14px; color: var(--el-text-color-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.search-item-time { font-size: 12px; color: var(--el-text-color-placeholder); flex-shrink: 0; }

.search-empty { padding: 32px; text-align: center; font-size: 13px; color: var(--el-text-color-placeholder); }
</style>
