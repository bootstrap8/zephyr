<script lang="ts" setup>
import { computed, onMounted, onUpdated, onBeforeUnmount, nextTick, watch, ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { Message } from '@/types/chat'
import ThinkingBlock from './ThinkingBlock.vue'
import ToolCallCard from './ToolCallCard.vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const props = defineProps<{ message: Message; isLast: boolean }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang }).value } catch {}
    }
    return ''
  }
})

const streaming = computed(() => props.isLast)
const mdBodyRef = ref<HTMLElement>()
let rafId = 0
let lastRendered = ''

function renderMarkdown() {
  if (!mdBodyRef.value) return
  const html = props.message.role === 'assistant' ? md.render(props.message.content) : ''
  if (html !== lastRendered) {
    lastRendered = html
    mdBodyRef.value.innerHTML = html
    nextTick(setupCodeBlocks)
  }
}

// rAF throttled rendering during streaming
watch(() => props.message.content, () => {
  if (props.isLast) {
    cancelAnimationFrame(rafId)
    rafId = requestAnimationFrame(renderMarkdown)
  } else {
    renderMarkdown()
  }
})

onMounted(() => {
  if (!props.isLast) renderMarkdown()
})

onBeforeUnmount(() => cancelAnimationFrame(rafId))

function setupCodeBlocks() {
  nextTick(() => {
    if (!mdBodyRef.value) return
    mdBodyRef.value.querySelectorAll('pre').forEach((pre) => {
      if (pre.parentElement?.classList.contains('code-block-wrapper')) return
      const wrapper = document.createElement('div')
      wrapper.className = 'code-block-wrapper'
      wrapper.innerHTML = `
        <div class="code-actions">
          <span class="code-icon code-copy" title="复制">
            <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
          </span>
          <span class="code-icon code-toggle" title="收起">
            <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>
          </span>
        </div>
      `
      const copyBtn = wrapper.querySelector('.code-copy')!
      copyBtn.addEventListener('click', async () => {
        const text = pre.textContent || ''
        await navigator.clipboard.writeText(text)
        copyBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#5db872" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'
        setTimeout(() => {
          copyBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>'
        }, 2000)
      })
      const toggleBtn = wrapper.querySelector('.code-toggle')!
      toggleBtn.addEventListener('click', () => {
        const collapsed = wrapper.classList.toggle('collapsed')
        toggleBtn.innerHTML = collapsed
          ? '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>'
          : '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>'
        toggleBtn.title = collapsed ? '展开' : '收起'
      })
      pre.parentNode!.insertBefore(wrapper, pre)
      wrapper.appendChild(pre)
    })
  })
}

onMounted(setupCodeBlocks)
onUpdated(setupCodeBlocks)
</script>

<template>
  <div class="msg-row" :class="message.role">
    <div v-if="message.role === 'user'" class="msg-bubble user-bubble">
      {{ message.content }}
    </div>
    <div v-else class="msg-bubble ai-bubble">
      <div class="ai-header">
        <div class="ai-avatar">
          <Icon icon="lucide:sparkles" />
        </div>
        <span class="ai-label">zephyr</span>
      </div>
      <div v-if="message.thinking" class="mb-2">
        <ThinkingBlock :content="message.thinking" :streaming="streaming && !message.content" />
      </div>
      <div v-for="tc in message.toolCalls" :key="tc.name" class="mb-2">
        <ToolCallCard :tool="tc" />
      </div>
      <div v-if="message.content" ref="mdBodyRef" class="markdown-body"></div>
    </div>
  </div>
</template>

<style scoped>
.msg-row { padding: 14px 24px; max-width: 820px; margin: 0 auto; }
.msg-row.user { display: flex; justify-content: flex-end; }
.msg-bubble { max-width: 88%; border-radius: 12px; padding: 12px 16px; font-size: var(--chat-font-size, 16px); line-height: 1.65; }
.user-bubble { background: var(--el-fill-color-light); color: var(--el-text-color-primary); border-radius: 12px 12px 4px 12px; }
.ai-bubble { background: transparent; border-radius: 12px 12px 12px 4px; color: var(--el-text-color-regular); padding-top: 0; }
.mb-2 { margin-bottom: 8px; }

.ai-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; padding-top: 8px; }
.ai-avatar { width: 28px; height: 28px; border-radius: 8px; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; color: var(--el-color-primary); font-size: 14px; flex-shrink: 0; }
.ai-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }

.markdown-body { font-size: inherit; }
.markdown-body :deep(h3) { font-family: Georgia, 'Times New Roman', serif; font-weight: 400; font-size: 1.25em; letter-spacing: -0.3px; margin: 14px 0 6px; color: var(--el-text-color-primary); }
.markdown-body :deep(p) { margin: 6px 0; }
.markdown-body :deep(ul) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(a) { color: var(--el-color-primary); }
.markdown-body :deep(code) { background: var(--el-fill-color); padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 0.85em; color: var(--el-color-primary-dark-2); }
.markdown-body :deep(pre) { background: #f5f0e8; color: #141413; border-radius: 8px; padding: 16px; margin: 0; overflow-x: auto; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 0.85em; line-height: 1.55; }
.markdown-body :deep(pre code) { background: transparent; color: inherit; padding: 0; border-radius: 0; font-size: inherit; }
</style>

<style>
.code-block-wrapper { position: relative; margin: 8px 0; background: #efe9de; border-radius: 8px; overflow: hidden; }
.code-block-wrapper pre { margin: 0; background: #f5f0e8; color: #141413; border-radius: 8px; padding: 14px 16px; overflow-x: auto; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 13px; line-height: 1.6; }
.code-actions { position: absolute; top: 8px; right: 8px; z-index: 1; display: flex; gap: 0; }
.code-icon { display: flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 6px; cursor: pointer; color: #8e8b82; transition: color 0.15s; }
.code-icon:hover { color: #141413; }
.code-block-wrapper.collapsed pre { max-height: 120px; overflow: hidden; }
.code-block-wrapper:not(.collapsed) pre { max-height: none; }
</style>
