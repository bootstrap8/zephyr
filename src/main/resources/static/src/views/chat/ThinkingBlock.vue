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
      <span>{{ streaming ? '思考中...' : '已深度思考' }}</span>
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
.thinking-block.streaming .thinking-dot { animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
.chevron { margin-left: auto; transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); }
.thinking-body { padding: 8px 0 8px 22px; font-size: 13px; color: var(--el-text-color-placeholder); line-height: 1.65; border-left: 2px solid var(--el-border-color); margin-left: 7px; white-space: pre-wrap; word-break: break-word; }
</style>
