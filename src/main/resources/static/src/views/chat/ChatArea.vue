<script lang="ts" setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { useChatStore } from '@/store/chat'
import { useConversationsStore } from '@/store/conversations'
import MessageBubble from './MessageBubble.vue'
import { Icon } from '@iconify/vue'

const chatStore = useChatStore()
const convStore = useConversationsStore()
const areaRef = ref<HTMLElement>()

const fontSize = ref(16)
const sizes = [12, 14, 16, 18, 20]

const STORAGE_KEY = 'zephyr-font-size'

onMounted(() => {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved && sizes.includes(Number(saved))) fontSize.value = Number(saved)
})

function changeFont(step: number) {
  const idx = sizes.indexOf(fontSize.value)
  const next = idx + step
  if (next >= 0 && next < sizes.length) {
    fontSize.value = sizes[next]
    localStorage.setItem(STORAGE_KEY, String(fontSize.value))
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (areaRef.value) areaRef.value.scrollTop = areaRef.value.scrollHeight
  })
}

watch(
  () => {
    const msgs = chatStore.messages
    if (msgs.length === 0) return ''
    const last = msgs[msgs.length - 1]
    return `${msgs.length}-${last.content.length}-${(last.thinking || '').length}`
  },
  scrollToBottom
)
</script>

<template>
  <div ref="areaRef" class="chat-area" :style="{ '--chat-font-size': fontSize + 'px' }">
    <div v-if="chatStore.messages.length > 0" class="font-ctrl">
      <button class="fc-btn" @click="changeFont(-1)" :disabled="fontSize <= sizes[0]">
        <Icon icon="lucide:minus" />
      </button>
      <span class="fc-val">{{ fontSize }}px</span>
      <button class="fc-btn" @click="changeFont(1)" :disabled="fontSize >= sizes[sizes.length - 1]">
        <Icon icon="lucide:plus" />
      </button>
    </div>
    <div v-if="chatStore.messages.length === 0" class="empty-state">
      <div class="empty-icon">z</div>
      <p class="empty-title">zephyr</p>
      <p class="empty-sub">开始一段新的对话</p>
    </div>
    <MessageBubble v-for="(msg, i) in chatStore.messages" :key="msg.id" :message="msg" :isLast="i === chatStore.messages.length - 1 && chatStore.streaming" />
  </div>
</template>

<style scoped>
.chat-area { flex: 1; overflow-y: auto; padding: 20px 0; position: relative; }
.chat-area::-webkit-scrollbar { width: 1px; }
.chat-area::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 1px; }
.font-ctrl { position: sticky; top: 0; float: right; display: inline-flex; align-items: center; gap: 4px; z-index: 10; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 8px; padding: 2px 4px; margin-right: 16px; }
.fc-btn { width: 24px; height: 24px; border-radius: 4px; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 12px; }
.fc-btn:hover:not(:disabled) { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.fc-btn:disabled { opacity: 0.3; cursor: default; }
.fc-val { font-size: 11px; color: var(--el-text-color-placeholder); min-width: 30px; text-align: center; font-variant-numeric: tabular-nums; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--el-text-color-secondary); }
.empty-icon { width: 48px; height: 48px; border-radius: 12px; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; font-family: Georgia, serif; font-size: 24px; color: var(--el-color-primary); margin-bottom: 12px; }
.empty-title { font-family: Georgia, 'Times New Roman', serif; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin-bottom: 4px; }
.empty-sub { font-size: 14px; color: var(--el-text-color-placeholder); }
</style>
