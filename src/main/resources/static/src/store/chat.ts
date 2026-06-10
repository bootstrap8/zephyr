import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Message } from '@/types/chat'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])
  const streaming = ref(false)
  const currentThinking = ref('')
  const sessionStartTime = ref(0)

  let tokenBuf = ''
  let thinkingBuf = ''
  let rafId = 0

  function flushTokens() {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') {
      if (tokenBuf) { last.content += tokenBuf; tokenBuf = '' }
      if (thinkingBuf) { last.thinking = (last.thinking || '') + thinkingBuf; thinkingBuf = '' }
    }
    rafId = 0
  }

  function addMessage(msg: Message) {
    flushTokens()
    messages.value.push(msg)
  }

  function appendToken(token: string) {
    tokenBuf += token
    scheduleFlush()
  }

  function setThinking(text: string) {
    currentThinking.value = text
  }

  function updateLastThinking(text: string) {
    thinkingBuf += text
    scheduleFlush()
  }

  function scheduleFlush() {
    if (!rafId) rafId = requestAnimationFrame(flushTokens)
  }

  function clearMessages() {
    cancelAnimationFrame(rafId); rafId = 0
    tokenBuf = ''; thinkingBuf = ''
    messages.value = []
    currentThinking.value = ''
    sessionStartTime.value = 0
  }

  function startSession() {
    if (sessionStartTime.value === 0) sessionStartTime.value = Date.now()
  }

  function pruneEmptyAssistant() {
    flushTokens()
    const msgs = messages.value
    if (msgs.length > 0) {
      const last = msgs[msgs.length - 1]
      if (last.role === 'assistant' && !(last.content || '').trim() && (!last.toolCalls || last.toolCalls.length === 0)) {
        msgs.pop()
      }
    }
  }

  return { messages, streaming, currentThinking, sessionStartTime, addMessage, appendToken, setThinking, updateLastThinking, clearMessages, startSession, pruneEmptyAssistant }
})
