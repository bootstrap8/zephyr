<script lang="ts" setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { useChatStore } from '@/store/chat'
import { Icon } from '@iconify/vue'

const settingsStore = useSettingsStore()
const chatStore = useChatStore()
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval>

function updateInterval(): number {
  const elapsed = now.value - chatStore.sessionStartTime
  return elapsed < 60000 ? 1000 : 60000
}

function scheduleNext() {
  timer = setTimeout(() => {
    now.value = Date.now()
    scheduleNext()
  }, updateInterval())
}

onMounted(() => {
  settingsStore.loadModels()
  scheduleNext()
})

onBeforeUnmount(() => clearTimeout(timer))

const ctxPercent = computed(() => settingsStore.contextPercent)
const ctxUsedStr = computed(() => {
  const k = settingsStore.contextUsed / 1024
  return k >= 1024 ? (k / 1024).toFixed(1) + 'M' : k.toFixed(1) + 'K'
})
const ctxTotalStr = computed(() => {
  const k = settingsStore.contextTotal / 1024
  return k >= 1024 ? (k / 1024).toFixed(0) + 'M' : (k / 1024).toFixed(0) + 'K'
})

function hexToHsl(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const d = max - min
  let h = 0
  const l = (max + min) / 2
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  if (d !== 0) {
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) * 60
    else if (max === g) h = ((b - r) / d + 2) * 60
    else h = ((r - g) / d + 4) * 60
  }
  return [h, s * 100, l * 100]
}

function hslInterpolate(hex1: string, hex2: string, t: number): string {
  const [h1, s1, l1] = hexToHsl(hex1)
  const [h2, s2, l2] = hexToHsl(hex2)
  const h = h1 + (h2 - h1) * t
  const s = s1 + (s2 - s1) * t
  const l = l1 + (l2 - l1) * t
  return `hsl(${Math.round(h)}, ${Math.round(s)}%, ${Math.round(l)}%)`
}

const ctxColor = computed(() => {
  const p = ctxPercent.value
  if (p <= 40) return '#5db872'
  if (p <= 70) return hslInterpolate('#5db872', '#e8a55a', (p - 40) / 30)
  return hslInterpolate('#e8a55a', '#c64545', Math.min((p - 70) / 30, 1))
})

const duration = computed(() => {
  if (!chatStore.sessionStartTime) return ''
  const elapsed = Math.max(0, Math.floor((now.value - chatStore.sessionStartTime) / 1000))
  const h = Math.floor(elapsed / 3600)
  const m = Math.floor((elapsed % 3600) / 60)
  const s = elapsed % 60
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`
  if (m > 0) return `${m}m ${String(s).padStart(2, '0')}s`
  return `${s}s`
})
</script>

<template>
  <div class="status-bar">
    <div class="status-item">
      <Icon icon="lucide:bot" class="s-icon" />
      <span>{{ settingsStore.currentModel }}</span>
    </div>

    <div class="ctx-group">
      <div class="ctx-bar">
        <div class="ctx-fill" :style="{ width: ctxPercent + '%', background: ctxColor }"></div>
      </div>
      <span class="ctx-text">{{ ctxUsedStr }} / {{ ctxTotalStr }}</span>
      <span class="ctx-pct" :style="{ color: ctxColor }">{{ ctxPercent }}%</span>
    </div>

    <span class="spacer"></span>

    <div v-if="duration" class="duration">
      <Icon icon="lucide:clock" class="dur-icon" />
      <span>{{ duration }}</span>
    </div>
  </div>
</template>

<style scoped>
.status-bar {
  padding: 6px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  border-top: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}
.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
}
.s-icon { font-size: 14px; flex-shrink: 0; color: var(--el-color-primary); }
.ctx-group { display: flex; align-items: center; gap: 6px; }
.ctx-bar { width: 80px; height: 5px; border-radius: 3px; background: var(--el-border-color); overflow: hidden; }
.ctx-fill { height: 100%; border-radius: 3px; transition: width 0.3s, background 0.3s; }
.ctx-text { font-size: 11px; color: var(--el-text-color-placeholder); white-space: nowrap; }
.ctx-pct { font-size: 11px; font-weight: 600; white-space: nowrap; }
.spacer { flex: 1; }
.duration { display: flex; align-items: center; gap: 4px; white-space: nowrap; color: var(--el-text-color-secondary); font-size: 11px; }
.dur-icon { font-size: 13px; }
</style>
