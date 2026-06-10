<script lang="ts" setup>
import { ref, watch, computed } from 'vue'
import { Icon } from '@iconify/vue'

const props = defineProps<{ content: string; streaming: boolean }>()
const collapsed = ref(true)
watch(() => props.streaming, (v) => { if (v) collapsed.value = false })

const hasContent = computed(() => props.content && props.content.length > 0)
</script>

<template>
  <div class="thinking-block" :class="{ collapsed, streaming }">
    <div class="thinking-header" @click="collapsed = !collapsed">
      <Icon :icon="streaming ? 'svg-spinners:3-dots-scale' : 'lucide:brain'" class="thinking-dot" />
      <span class="thinking-text">
        <template v-if="streaming">
          思考中<span class="dot-anim"><i>.</i><i>.</i><i>.</i></span>
        </template>
        <template v-else>已深度思考</template>
      </span>
      <Icon :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'" class="chevron" />
    </div>
    <div v-if="hasContent && !collapsed" class="thinking-body">{{ content }}</div>
  </div>
</template>

<style scoped>
.thinking-block { margin: 8px 0; }
.thinking-header { display: flex; align-items: center; gap: 6px; padding: 6px 0; cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; }
.thinking-header:hover { color: var(--el-text-color-primary); }
.thinking-dot { color: var(--el-color-primary); font-size: 16px; flex-shrink: 0; }
.thinking-block.streaming .thinking-dot { animation: thinkPulse 1.2s ease-in-out infinite; }
@keyframes thinkPulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.5; transform: scale(0.85); } }

.thinking-text { display: inline-flex; }

/* 思考中动态省略号 */
.dot-anim i {
  font-style: normal;
  animation: dotBounce 1.4s ease-in-out infinite;
}
.dot-anim i:nth-child(1) { animation-delay: 0s; }
.dot-anim i:nth-child(2) { animation-delay: 0.2s; }
.dot-anim i:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotBounce {
  0%, 80%, 100% { opacity: 0; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}

.chevron { transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); }
.thinking-body { padding: 8px 0 8px 22px; font-size: 13px; color: var(--el-text-color-placeholder); line-height: 1.65; border-left: 2px solid var(--el-border-color); margin-left: 7px; white-space: pre-wrap; word-break: break-word; }
</style>
