<script lang="ts" setup>
import { computed, onMounted, onUpdated, onBeforeUnmount, nextTick, watch, ref } from 'vue'
import { Icon } from '@iconify/vue'
import type { Message } from '@/types/chat'
import ThinkingBlock from './ThinkingBlock.vue'
import ToolCallCard from './ToolCallCard.vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

import { useWorkspaceStore } from '@/store/workspace'

const workspaceStore = useWorkspaceStore()

function artifactUrl(filePath: string) {
  const wsId = workspaceStore.currentId
  if (!wsId) return '#'
  const encodedPath = filePath.split('/').map(encodeURIComponent).join('/')
  return `./chat/files/${wsId}/${encodedPath}`
}

function previewable(contentType: string) {
  return contentType.startsWith('text/html')
    || contentType.startsWith('image/')
    || contentType === 'application/pdf'
    || contentType === 'application/javascript'
    || contentType === 'application/json'
}

function formatFileSize(bytes: number) {
  if (bytes === 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

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
const toolExpanded = ref(false)
let rafId = 0
let lastRendered = ''

const contentSummary = computed(() => {
  const c = props.message.content || ''
  if (c.length <= 200) return c.substring(0, 200)
  return c.substring(0, 200) + '...'
})

function renderMarkdown() {
  if (!mdBodyRef.value) return
  const html = props.message.role !== 'user' && props.message.role !== 'tool'
    ? md.render(props.message.content) : ''
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
          <span class="code-icon code-download" :title="langData.msgBubble_download || '下载'">
            <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/></svg>
          </span>
          <span class="code-icon code-copy" :title="langData.msgBubble_copy">
            <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
          </span>
          <span class="code-icon code-toggle" :title="langData.msgBubble_collapse">
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
      const downloadBtn = wrapper.querySelector('.code-download')!
      downloadBtn.addEventListener('click', () => {
        const text = pre.textContent || ''
        const lang = pre.className.replace('language-', '').replace('lang-', '').trim()
        const extMap: Record<string, string> = {
          html: '.html', htm: '.html', css: '.css', js: '.js', ts: '.ts', json: '.json',
          xml: '.xml', yaml: '.yml', yml: '.yml', md: '.md', txt: '.txt',
          py: '.py', java: '.java', go: '.go', rs: '.rs', c: '.c', cpp: '.cpp',
          sh: '.sh', bash: '.sh', sql: '.sql', svg: '.svg'
        }
        const ext = extMap[lang] || (lang ? '.' + lang : '.txt')
        const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = 'code' + ext
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
      })
      const toggleBtn = wrapper.querySelector('.code-toggle')!
      toggleBtn.addEventListener('click', () => {
        const collapsed = wrapper.classList.toggle('collapsed')
        toggleBtn.innerHTML = collapsed
          ? '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>'
          : '<svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>'
        ;(toggleBtn as HTMLElement).title = collapsed ? langData.msgBubble_expand : langData.msgBubble_collapse
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
    <div v-else-if="message.role === 'tool'" class="tool-bubble">
      <div class="tool-result-header" @click="toolExpanded = !toolExpanded">
        <Icon icon="lucide:wrench" class="tool-result-icon" />
        <span class="tool-result-label">{{ langData.msgBubble_toolResult }}</span>
        <span class="tool-result-summary">{{ contentSummary }}</span>
        <Icon :icon="toolExpanded ? 'lucide:chevron-up' : 'lucide:chevron-down'" class="tool-result-toggle" />
      </div>
      <pre v-if="toolExpanded" class="tool-result-body">{{ message.content }}</pre>
    </div>
    <div v-else class="msg-bubble ai-bubble">
      <div class="ai-header">
        <div class="ai-avatar">
          <Icon icon="lucide:sparkles" />
        </div>
        <span class="ai-label">zephyr</span>
      </div>
      <div v-if="message.thinking" class="mb-2">
        <ThinkingBlock :content="message.thinking" :animating="streaming" />
      </div>
      <div v-for="tc in message.toolCalls" :key="tc.name" class="mb-2">
        <ToolCallCard :tool="tc" />
      </div>
      <div v-if="message.content" ref="mdBodyRef" class="markdown-body"></div>
      <div v-if="message.artifacts?.length" class="artifacts-row">
        <div v-for="a in message.artifacts" :key="a.path" class="artifact-card">
          <Icon :icon="previewable(a.contentType) ? 'lucide:eye' : 'lucide:download'" class="artifact-icon" />
          <span class="artifact-name">{{ a.name }}</span>
          <span class="artifact-size">{{ formatFileSize(a.size) }}</span>
          <a v-if="previewable(a.contentType)" :href="artifactUrl(a.path)" target="_blank" class="artifact-btn artifact-preview">
            <Icon icon="lucide:external-link" /> 打开预览
          </a>
          <a :href="artifactUrl(a.path) + '?download=1'" class="artifact-btn artifact-download">
            <Icon icon="lucide:download" /> 下载
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.msg-row { padding: 14px 24px; max-width: 820px; margin: 0 auto; }
.msg-row.user { display: flex; justify-content: flex-end; }
.msg-bubble { max-width: 88%; border-radius: 12px; padding: 12px 16px; font-size: var(--chat-font-size, 16px); line-height: 1.65; }
.user-bubble { background: var(--el-fill-color-light); color: var(--el-text-color-primary); border-radius: 12px 12px 4px 12px; white-space: pre-wrap; }
.ai-bubble { background: transparent; border-radius: 12px 12px 12px 4px; color: var(--el-text-color-regular); padding-top: 0; }
.mb-2 { margin-bottom: 8px; }

.ai-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; padding-top: 8px; }
.ai-avatar { width: 28px; height: 28px; border-radius: 8px; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; color: var(--el-color-primary); font-size: 14px; flex-shrink: 0; }
.ai-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }

.tool-bubble { margin-left: 16px; max-width: calc(88% - 32px); box-sizing: border-box; border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden; padding: 8px 12px; font-size: var(--chat-font-size, 16px); line-height: 1.65; }
.tool-result-header { display: flex; align-items: center; gap: 8px; cursor: pointer; user-select: none; font-size: 12px; color: var(--el-text-color-secondary); }
.tool-result-header:hover { background: var(--el-fill-color); }
.tool-result-icon { font-size: 14px; color: var(--el-color-primary); flex-shrink: 0; }
.tool-result-label { font-weight: 500; flex-shrink: 0; }
.tool-result-summary { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--el-text-color-placeholder); }
.tool-result-toggle { font-size: 14px; flex-shrink: 0; }
.tool-result-body { padding: 8px 0 0; margin: 8px 0 0; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 11px; line-height: 1.4; color: var(--el-text-color-secondary); max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all; border-top: 1px solid var(--el-border-color); }

.markdown-body { font-size: inherit; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) { font-family: Georgia, 'Times New Roman', serif; font-weight: 400; margin: 16px 0 8px; color: var(--el-text-color-primary); }
.markdown-body :deep(h1) { font-size: 1.5em; }
.markdown-body :deep(h2) { font-size: 1.35em; }
.markdown-body :deep(h3) { font-size: 1.2em; letter-spacing: -0.3px; }
.markdown-body :deep(h4) { font-size: 1.1em; }
.markdown-body :deep(h5),
.markdown-body :deep(h6) { font-size: 1em; }
.markdown-body :deep(hr) { display: none; }
.markdown-body :deep(p) { margin: 6px 0; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 24px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(a) { color: var(--el-color-primary); }
.markdown-body :deep(blockquote) { border-left: 3px solid var(--el-color-primary-light-5); padding: 4px 12px; margin: 8px 0; color: var(--el-text-color-secondary); }
.markdown-body :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; }
.markdown-body :deep(th),
.markdown-body :deep(td) { border: 1px solid var(--el-border-color); padding: 6px 10px; text-align: left; font-size: 0.95em; }
.markdown-body :deep(th) { background: var(--el-fill-color-light); font-weight: 600; }
.markdown-body :deep(code) { background: var(--el-fill-color); padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 0.85em; color: var(--el-color-primary-dark-2); }
.markdown-body :deep(pre) { background: #f5f0e8; color: #141413; border-radius: 8px; padding: 16px; margin: 0; overflow-x: auto; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 0.85em; line-height: 1.55; white-space: pre-wrap; word-break: break-word; overflow-wrap: break-word; }
.markdown-body :deep(pre)::-webkit-scrollbar { width: 2px; height: 2px; }
.markdown-body :deep(pre)::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.20); border-radius: 1px; }
.markdown-body :deep(pre)::-webkit-scrollbar-track { background: transparent; }
.markdown-body :deep(pre code) { background: transparent; color: inherit; padding: 0; border-radius: 0; font-size: inherit; }

.artifacts-row { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.artifact-card { display: flex; align-items: center; gap: 6px; padding: 6px 10px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); border-radius: 6px; font-size: 12px; }
.artifact-icon { color: var(--el-color-primary); font-size: 14px; flex-shrink: 0; }
.artifact-name { font-weight: 500; color: var(--el-text-color-primary); max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.artifact-size { color: var(--el-text-color-placeholder); font-size: 11px; flex-shrink: 0; }
.artifact-btn { display: inline-flex; align-items: center; gap: 3px; color: var(--el-color-primary); text-decoration: none; font-size: 11px; padding: 2px 6px; border-radius: 4px; transition: background 0.15s; white-space: nowrap; }
.artifact-btn:hover { background: var(--el-color-primary-light-9); }
</style>

<style>
.code-block-wrapper { position: relative; margin: 8px 0 0; background: #efe9de; border-radius: 8px; overflow: hidden; }
.code-block-wrapper pre { margin: 0; background: #f5f0e8; color: #141413; border-radius: 8px; padding: 14px 16px; overflow-x: auto; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; overflow-wrap: break-word; }
.code-block-wrapper pre::-webkit-scrollbar { width: 2px; height: 2px; }
.code-block-wrapper pre::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.20); border-radius: 1px; }
.code-block-wrapper pre::-webkit-scrollbar-track { background: transparent; }
.code-actions { position: absolute; top: 8px; right: 8px; z-index: 1; display: flex; gap: 0; }
.code-icon { display: flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 4px; cursor: pointer; color: #8e8b82; transition: color 0.15s; }
.code-icon:hover { color: #141413; }
.code-block-wrapper.collapsed pre { max-height: 120px; overflow-y: auto; }
.code-block-wrapper.collapsed pre::-webkit-scrollbar { width: 0; height: 0; }
.code-block-wrapper:not(.collapsed) pre { max-height: none; }

html.dark .artifact-card { background: var(--el-fill-color); border-color: var(--el-border-color); }
</style>
