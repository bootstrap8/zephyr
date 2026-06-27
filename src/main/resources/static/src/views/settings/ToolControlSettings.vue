<script lang="ts" setup>
import { onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'

const store = useSettingsStore()
const langData = getLangData()

onMounted(async () => {
  await store.loadBuiltinToolControls()
})

async function toggle(toolName: string, requireAdmin: number) {
  await store.toggleBuiltinTool(toolName, requireAdmin)
}
</script>

<template>
  <div class="tool-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.toolControl_title }}</h1>
      </div>
    </div>
    <p class="subtitle">{{ langData.toolControl_subtitle }}</p>

    <div class="tool-table">
      <div class="table-header">
        <span class="col-name">{{ langData.toolControl_toolName }}</span>
        <span class="col-desc">{{ langData.toolControl_description }}</span>
        <span class="col-toggle">{{ langData.toolControl_requireAdmin }}</span>
      </div>
      <div v-for="t in store.builtinToolControls" :key="t.toolName" class="table-row">
        <span class="col-name">{{ t.toolName }}</span>
        <span class="col-desc">{{ t.description }}</span>
        <span class="col-toggle">
          <label class="toggle-switch">
            <input type="checkbox" :checked="t.requireAdmin === 1" @change="toggle(t.toolName, t.requireAdmin === 1 ? 0 : 1)" />
            <span class="toggle-slider"></span>
          </label>
          <span class="toggle-label">{{ t.requireAdmin === 1 ? langData.toolControl_adminOnly : langData.toolControl_allUsers }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

.tool-table { background: var(--el-fill-color-lighter); border-radius: 12px; overflow: hidden; }
.table-header { display: flex; align-items: center; padding: 14px 24px; border-bottom: 1px solid var(--el-border-color); font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); text-transform: uppercase; letter-spacing: 0.5px; }
.table-row { display: flex; align-items: center; padding: 18px 24px; }
.table-row + .table-row { border-top: 1px solid var(--el-border-color-lighter); }
.table-row:hover { background: var(--el-fill-color-light); }

.col-name { width: 170px; font-family: monospace; font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); flex-shrink: 0; }
.col-desc { flex: 1; font-size: 13px; color: var(--el-text-color-secondary); min-width: 0; }
.col-toggle { width: 200px; display: flex; align-items: center; gap: 10px; flex-shrink: 0; justify-content: flex-end; }
.toggle-label { font-size: 13px; color: var(--el-text-color-secondary); white-space: nowrap; }

.toggle-switch { position: relative; width: 38px; height: 22px; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 99px; cursor: pointer; transition: background 200ms; }
.toggle-slider::after { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: transform 200ms; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::after { transform: translateX(16px); }

html.dark .tool-table { background: var(--el-bg-color); }
html.dark .table-row:hover { background: var(--el-fill-color-lighter); }

@media (max-width: 640px) {
  .tool-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
  .table-row { flex-wrap: wrap; gap: 8px; }
  .col-name { width: 100%; }
  .col-toggle { width: 100%; justify-content: flex-start; }
}
</style>
