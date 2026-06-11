<script lang="ts" setup>
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

const props = defineProps<{
  content: string
  animating: boolean   // 响应是否仍在生成中（控制动效和文字）
}>()

const collapsed = ref(true)

const hasContent = computed(() => props.content && props.content.length > 0)

const preview = computed(() => {
  if (!hasContent.value) return ''
  const lines = props.content.split('\n')
  if (lines.length <= 3) return props.content
  return lines.slice(-3).join('\n')
})

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

watch(() => props.animating, (v) => { if (v) startTimer(); else stopTimer() })

onMounted(() => { if (props.animating) startTimer() })
onBeforeUnmount(stopTimer)

let rafId = 0

function stopEffect() {
  cancelAnimationFrame(rafId)
  rafId = 0
}
</script>

<template>
  <div class="thinking-block" :class="{ collapsed, animating }">
    <div class="thinking-header" @click="collapsed = !collapsed">
      <span class="thinking-text">
        <template v-if="animating">
          {{ langData.thinkingBlock_thinking }}<span class="dot-anim"><i>.</i><i>.</i><i>.</i></span>
          <span v-if="elapsedSeconds > 0" class="elapsed-time">{{ elapsedText }}</span>
        </template>
        <template v-else>
          <Icon icon="lucide:brain" class="brain-icon" /> {{ langData.thinkingBlock_deepThought }}
        </template>
      </span>
      <Icon :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'" class="chevron" />
    </div>
    <div v-if="hasContent && collapsed && animating" class="thinking-preview">{{ preview }}</div>
    <div v-if="hasContent && !collapsed" class="thinking-body">{{ content }}</div>
  </div>
</template>

<style scoped>
.thinking-block { margin: 8px 0; }
.thinking-header { display: flex; align-items: center; gap: 6px; padding: 6px 0; cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; }
.thinking-header:hover { color: var(--el-text-color-primary); }

.thinking-text { display: inline-flex; align-items: baseline; gap: 6px; }
.elapsed-time { font-size: 11px; color: var(--el-text-color-placeholder); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.brain-icon { color: var(--el-color-primary); font-size: 16px; flex-shrink: 0; margin-right: 4px; }

.chevron { transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); flex-shrink: 0; margin-left: 4px; }
.thinking-preview { padding: 4px 0 4px 22px; font-size: 13px; color: var(--el-text-color-placeholder); line-height: 1.65; border-left: 2px solid var(--el-border-color); margin-left: 7px; white-space: pre-wrap; word-break: break-word; max-height: 72px; overflow: hidden; position: relative; }
.thinking-preview::after { content: ''; position: absolute; bottom: 0; left: 22px; right: 0; height: 36px; background: linear-gradient(to bottom, transparent, var(--el-bg-color)); pointer-events: none; }
.thinking-body { padding: 8px 0 8px 22px; font-size: 13px; color: var(--el-text-color-placeholder); line-height: 1.65; border-left: 2px solid var(--el-border-color); margin-left: 7px; white-space: pre-wrap; word-break: break-word; }
</style>

<!-- @keyframes 放在非 scoped 块，避免 Vue 3 的 scoped 哈希重命名问题 -->
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
