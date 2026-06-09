<script lang="ts" setup>
import { onMounted, ref, watch } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { useChatStore } from '@/store/chat'
import { useSettingsStore } from '@/store/settings'
import ChatSidebar from './ChatSidebar.vue'
import ChatArea from './ChatArea.vue'
import InputArea from './InputArea.vue'
import StatusBar from './StatusBar.vue'
import CommandPalette from './CommandPalette.vue'
import SettingsPanel from './SettingsPanel.vue'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const convStore = useConversationsStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const showSettings = ref(false)
let abortController: AbortController | null = null

function refreshConversationList() {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
}

function newChat() {
  chatStore.clearMessages()
  convStore.currentId = null
}

function onSend(text: string) {
  if (abortController) abortController.abort()
  abortController = new AbortController()

  chatStore.addMessage({ id: '', role: 'user', content: text, timestamp: Date.now() / 1000 })
  chatStore.addMessage({ id: '', role: 'assistant', content: '', timestamp: Date.now() / 1000 })
  chatStore.streaming = true

  axios({
    url: `/chat/send`,
    method: 'post',
    data: { conversationId: convStore.currentId, message: text },
    responseType: 'text',
    signal: abortController.signal,
    onDownloadProgress(evt: any) {
      const raw = evt.event?.target?.responseText || evt.currentTarget?.responseText || ''
      const lines = raw.split('\n')
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        try {
          const event = JSON.parse(line.substring(5).trim())
          if (event.type === 'token') {
            chatStore.appendToken(event.content)
          } else if (event.type === 'thinking') {
            chatStore.updateLastThinking(event.content)
          } else if (event.type === 'meta') {
            convStore.currentId = event.content
            refreshConversationList()
          } else if (event.type === 'done') {
            refreshConversationList()
            chatStore.streaming = false
          } else if (event.type === 'error') {
            chatStore.appendToken('\n\n[错误] ' + (event.content || '请求失败'))
            chatStore.streaming = false
          }
        } catch (_) {}
      }
    }
  }).catch(() => {
    chatStore.appendToken('\n\n[请求失败]')
    chatStore.streaming = false
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
        const msgs = (res.data.body || []).map((m: any) => ({
          id: m.id,
          role: m.role,
          content: m.content || '',
          thinking: m.thinking || '',
          toolCalls: m.toolCalls || [],
          timestamp: m.timestamp
        }))
        chatStore.clearMessages()
        msgs.forEach((m: any) => chatStore.addMessage(m))
      }
    })
}

watch(() => convStore.currentId, (newId) => {
  if (newId && !chatStore.streaming) restoreConversation(newId)
})

onMounted(() => {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
  settingsStore.loadModels()
  settingsStore.loadMcpServers()
})
</script>

<template>
  <div class="app-layout">
    <ChatSidebar @open-settings="showSettings = true" @new-chat="newChat" />
    <div class="main-area">
      <div class="top-toolbar" :class="{ show: convStore.sidebarCollapsed }">
        <button class="tb-btn" @click="convStore.toggleSidebar()" title="展开侧边栏">
          <Icon icon="lucide:panel-left-open" />
        </button>
        <span class="tb-logo" @click="convStore.toggleSidebar()">zephyr</span>
        <span class="tb-divider"></span>
        <button class="tb-btn" title="新对话" @click="newChat">
          <Icon icon="lucide:square-pen" />
        </button>
        <button class="tb-btn" title="搜索会话">
          <Icon icon="lucide:search" />
        </button>
        <button class="tb-btn" title="历史会话">
          <Icon icon="lucide:history" />
        </button>
      </div>
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
</style>
