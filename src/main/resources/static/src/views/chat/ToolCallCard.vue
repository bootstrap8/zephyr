<script lang="ts" setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'
import type { ToolCall } from '@/types/chat'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

const props = defineProps<{
  tool: ToolCall
}>()

const collapsed = ref(true)

const startTime = ref(0)
const elapsedSeconds = ref(0)
let timerId: ReturnType<typeof setInterval> | null = null

function startTimer() {
  stopTimer()
  startTime.value = Date.now()
  elapsedSeconds.value = 0
  timerId = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startTime.value) / 1000)
  }, 1000)
}

function stopTimer() {
  if (timerId) { clearInterval(timerId); timerId = null }
}

const elapsedText = computed(() => {
  const s = elapsedSeconds.value
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  return `${m}m${rs}s`
})

onBeforeUnmount(stopTimer)

const CMD_MAX_LEN = 20

const BUILTIN_TOOLS = new Set([
  'use_skill', 'use_memory', 'search_knowledge',
  'execute_shell', 'list_processes', 'kill_process'
])

function truncate(s: string) {
  return s.length > CMD_MAX_LEN ? s.substring(0, CMD_MAX_LEN) + '...' : s
}

const displayName = computed(() => {
  const name = props.tool.name
  const input = props.tool.input
  if (name === 'use_skill' && input?.skill_name) return `use_skill(${input.skill_name})`
  if (name === 'use_memory' && input?.memory_name) return `use_memory(${input.memory_name})`
  if (name === 'search_knowledge' && input?.query) return `search_knowledge(${truncate(String(input.query))})`
  if (name === 'execute_shell' && input?.command) return `execute_shell(${truncate(String(input.command))})`
  if (name === 'kill_process' && input?.pid != null) return `kill_process(PID ${input.pid})`
  if (!BUILTIN_TOOLS.has(name)) return `mcp(${name})`
  return name
})

const inputStr = computed(() => {
  if (!props.tool.input || Object.keys(props.tool.input).length === 0) return ''
  return JSON.stringify(props.tool.input, null, 2)
})

const hasDetails = computed(() => inputStr.value || props.tool.output)

const isRunning = computed(() => props.tool.status === 'running')

watch(isRunning, (v) => { if (v) startTimer(); else stopTimer() })

onMounted(() => { if (isRunning.value) startTimer() })
</script>

<template>
  <div class="tool-call-block" :class="{ collapsed, running: isRunning }">
    <div class="tool-header" @click="collapsed = !collapsed">
      <span class="tool-text">
        <Icon icon="lucide:wrench" class="tool-icon" />
        {{ displayName }}
        <template v-if="isRunning">
          <span class="dot-anim"><i>.</i><i>.</i><i>.</i></span>
        </template>
        <span v-if="isRunning && elapsedSeconds > 0" class="elapsed-time">{{ elapsedText }}</span>
      </span>
      <Icon v-if="hasDetails" :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'" class="chevron" />
      <span v-if="tool.status !== 'running'" class="tool-status" :class="tool.status">
        <Icon v-if="tool.status === 'success'" icon="lucide:check-circle" style="font-size:11px" />
        <Icon v-else-if="tool.status === 'error'" icon="lucide:x-circle" style="font-size:11px" />
        <Icon v-else-if="tool.status === 'rejected'" icon="lucide:shield-alert" style="font-size:11px" />
        <Icon v-else icon="lucide:loader-circle" style="font-size:11px" />
        {{ tool.status === 'success' ? langData.toolCard_success : tool.status === 'error' ? langData.toolCard_failed : tool.status === 'rejected' ? langData.toolCard_rejected : langData.toolCard_running }}
      </span>
    </div>
    <!-- 折叠时显示参数预览（仅 animating 且有 input 时） -->
    <div v-if="collapsed && isRunning && inputStr" class="tool-preview">{{ inputStr }}</div>
    <!-- 展开详情 -->
    <div v-if="!collapsed && hasDetails" class="tool-body">
      <div v-if="inputStr" class="tool-section">
        <div class="tool-section-label">{{ langData.toolCard_inputParams }}</div>
        <pre class="tool-json">{{ inputStr }}</pre>
      </div>
      <div v-if="tool.output" class="tool-section">
        <div class="tool-section-label">{{ langData.toolCard_result }}</div>
        <pre class="tool-json">{{ tool.output }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-block { margin: 8px 0; }
.tool-header { display: flex; align-items: center; gap: 6px; padding: 6px 0; cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; }
.tool-header:hover { color: var(--el-text-color-primary); }

.tool-text { display: inline-flex; align-items: baseline; gap: 6px; }
.tool-icon { color: var(--el-text-color-secondary); font-size: 14px; flex-shrink: 0; }
.elapsed-time { font-size: 11px; color: var(--el-text-color-placeholder); font-variant-numeric: tabular-nums; flex-shrink: 0; }

.tool-status { margin-left: auto; font-size: 11px; padding: 1px 8px; border-radius: 99px; display: flex; align-items: center; gap: 3px; flex-shrink: 0; }
.tool-status.success { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.tool-status.error { background: rgba(198,69,69,0.12); color: var(--el-color-danger); }
.tool-status.rejected { background: rgba(230,162,60,0.12); color: var(--el-color-warning); }
.tool-status.running { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }

.chevron { transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); flex-shrink: 0; margin-left: 4px; }

.tool-preview { padding: 4px 0 4px 22px; font-size: 12px; color: var(--el-text-color-placeholder); line-height: 1.4; border-left: 2px solid var(--el-border-color); margin-left: 7px; font-family: 'JetBrains Mono', monospace; white-space: pre-wrap; word-break: break-word; max-height: 36px; overflow: hidden; position: relative; }
.tool-preview::after { content: ''; position: absolute; bottom: 0; left: 22px; right: 0; height: 24px; background: linear-gradient(to bottom, transparent, var(--el-bg-color)); pointer-events: none; }

.tool-body { padding: 8px 0 8px 22px; border-left: 2px solid var(--el-border-color); margin-left: 7px; }
.tool-section { margin-bottom: 8px; }
.tool-section:last-child { margin-bottom: 0; }
.tool-section-label { font-size: 11px; color: var(--el-text-color-placeholder); margin-bottom: 4px; font-weight: 500; }
.tool-json { font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color); padding: 8px 12px; border-radius: 6px; margin: 0; white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto; }
</style>

<!-- @keyframes 放在非 scoped 块 -->
<style>
.dot-anim {
  font-size: 20px;
  line-height: 1;
  vertical-align: baseline;
  margin-left: 2px;
}
.dot-anim i {
  font-style: normal;
  font-weight: 700;
  animation: dotBounce 1.4s ease-in-out infinite;
}
.dot-anim i:nth-child(1) { color: #cc785c; animation-delay: 0s; }
.dot-anim i:nth-child(2) { color: #e8a55a; animation-delay: 0.2s; }
.dot-anim i:nth-child(3) { color: #6366f1; animation-delay: 0.4s; }
@keyframes dotBounce {
  0%, 80%, 100% { opacity: 0.15; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
</style>
