<script lang="ts" setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()
const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const settingsStore = useSettingsStore()
const workspaceStore = useWorkspaceStore()

watch(() => props.visible, (v) => {
  if (v) {
    settingsStore.loadMcpServers(); settingsStore.loadSkills(); settingsStore.loadMemories()
    axios({ url: '/workspace/list', method: 'get' }).then(res => {
      if (res.data.state === 'OK') workspaceStore.setWorkspaces(res.data.body || [])
    }).catch(() => {})
  }
})

const isDark = ref(false)
if (typeof document !== 'undefined') isDark.value = document.documentElement.classList.contains('dark')

function goTo(path: string) {
  emit('close')
  router.push(path)
}

function toggleDark() {
  document.documentElement.classList.toggle('dark')
  isDark.value = document.documentElement.classList.contains('dark')
  localStorage.setItem('zephyr-dark', isDark.value ? '1' : '0')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="sp-overlay" @click="emit('close')"></div>
    <div v-if="visible" class="sp-card">
      <div class="sp-header">
        <span class="sp-title">{{ langData.settingsPanel_title }}</span>
        <button class="sp-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="sp-body">
        <div class="sp-item" @click="goTo('/settings/model')">
          <Icon icon="lucide:cpu" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_currentModel }}</span>
          <span class="sp-value">{{ settingsStore.currentModel }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/mcp')">
          <Icon icon="lucide:plug" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_mcpMgmt }}</span>
          <span class="sp-value">{{ settingsStore.mcpServers.length > 0 ? langData.settingsPanel_mcpCount.replace('{count}', settingsStore.mcpServers.length) : langData.settingsPanel_noMcp }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/skills')">
          <Icon icon="lucide:puzzle" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_skillMgmt }}</span>
          <span class="sp-value">{{ langData.settingsPanel_skillCount.replace('{count}', settingsStore.skills.filter((s: any) => s.enabled).length) }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/memory')">
          <Icon icon="lucide:hard-drive" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_memoryMgmt }}</span>
          <span class="sp-value">{{ settingsStore.memories.length > 0 ? langData.settingsPanel_mcpCount.replace('{count}', settingsStore.memories.length) : langData.settingsPanel_noMemory }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/knowledge')">
          <Icon icon="lucide:library" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_knowledgeMgmt }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/workspace')">
          <Icon icon="lucide:folder-open" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_workspace }}</span>
          <span class="sp-value">{{ workspaceStore.workspaces.length > 0 ? langData.settingsPanel_mcpCount.replace('{count}', workspaceStore.workspaces.length) : langData.settingsPanel_noMcp }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-divider"></div>
        <div class="sp-item" @click="toggleDark()">
          <Icon icon="lucide:moon" class="sp-item-icon" />
          <span>{{ langData.settingsPanel_darkMode }}</span>
          <span
            class="sp-switch"
            :class="{ on: isDark }"
            @click.stop="toggleDark()"
          ></span>
        </div>
      </div>
      <div class="sp-footer">
        <button class="sp-btn" @click="emit('close')">{{ langData.settingsPanel_close }}</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sp-overlay { position: fixed; inset: 0; z-index: 200; background: rgba(0,0,0,0.35); }
.sp-card { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); width: 400px; max-height: 80vh; background: var(--el-bg-color); border-radius: 12px; box-shadow: 0 16px 48px rgba(0,0,0,0.12); z-index: 201; display: flex; flex-direction: column; }
.sp-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid var(--el-border-color); }
.sp-title { font-family: Georgia, serif; font-size: 20px; letter-spacing: -0.3px; color: var(--el-text-color-primary); }
.sp-close { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 15px; }
.sp-close:hover { background: var(--el-fill-color-light); }
.sp-body { flex: 1; overflow-y: auto; padding: 8px 20px; }
.sp-body::-webkit-scrollbar { width: 2px; }
.sp-body::-webkit-scrollbar-thumb { background: var(--el-text-color-placeholder); border-radius: 1px; opacity: 0.8; }
html.dark .sp-body::-webkit-scrollbar-thumb { background: var(--el-text-color-placeholder); opacity: 0.8; }
.sp-item { display: flex; align-items: center; gap: 8px; padding: 12px 0; border-bottom: 1px solid var(--el-border-color); cursor: pointer; font-size: 14px; color: var(--el-text-color-primary); transition: color 0.1s; }
.sp-item:hover { color: var(--el-color-primary); }
.sp-item-icon { color: var(--el-text-color-secondary); font-size: 16px; flex-shrink: 0; }
.sp-value { color: var(--el-text-color-secondary); font-size: 13px; flex: 1; text-align: right; }
.sp-arrow { color: var(--el-text-color-placeholder); font-size: 14px; flex-shrink: 0; }
.sp-divider { height: 8px; }
.sp-switch { width: 44px; height: 24px; border-radius: 12px; background: var(--el-border-color); position: relative; cursor: pointer; transition: background 0.2s; flex-shrink: 0; }
.sp-switch.on { background: var(--el-color-primary); }
.sp-switch::after { content: ''; position: absolute; width: 20px; height: 20px; border-radius: 50%; background: #fff; top: 2px; left: 2px; transition: transform 0.2s; }
.sp-switch.on::after { transform: translateX(20px); }
.sp-footer { padding: 12px 20px; border-top: 1px solid var(--el-border-color); display: flex; justify-content: flex-end; }
.sp-btn { padding: 6px 18px; border-radius: 8px; border: none; background: var(--el-fill-color); color: var(--el-text-color-primary); cursor: pointer; font-size: 14px; font-family: inherit; }
.sp-btn:hover { background: var(--el-border-color); }

</style>
