<script lang="ts" setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { useWorkspaceStore } from '@/store/workspace'
import { useConversationsStore } from '@/store/conversations'
import { Icon } from '@iconify/vue'
import { msg } from '@/utils/Utils'
import axios from '@/network'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()
const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const settingsStore = useSettingsStore()
const workspaceStore = useWorkspaceStore()
const convStore = useConversationsStore()

const kbList = ref<any[]>([])
const selectedKbIds = ref<string[]>([])
const convKbLoading = ref(false)

watch(() => props.visible, (v) => {
  if (v) {
    settingsStore.loadMcpServers(); settingsStore.loadSkills(); settingsStore.loadMemories()
    axios({ url: '/workspace/list', method: 'get' }).then(res => {
      if (res.data.state === 'OK') workspaceStore.setWorkspaces(res.data.body || [])
    }).catch(() => {})
    loadKbData()
  }
})

function loadKbData() {
  const convId = convStore.currentId
  // Load KB list
  axios({ url: '/knowledge/kb/list', method: 'get' }).then(res => {
    if (res.data.state === 'OK') kbList.value = res.data.body || []
  }).catch(() => {})
  // Load selected KBs for current conversation
  if (convId) {
    convKbLoading.value = true
    axios({ url: '/knowledge/conversation/kb/list', method: 'get', params: { conversationId: convId } })
      .then(res => {
        convKbLoading.value = false
        if (res.data.state === 'OK') selectedKbIds.value = res.data.body || []
      })
      .catch(() => { convKbLoading.value = false })
  } else {
    selectedKbIds.value = []
  }
}

function toggleKb(kbId: string) {
  const convId = convStore.currentId
  if (!convId) return
  const idx = selectedKbIds.value.indexOf(kbId)
  if (idx >= 0) {
    selectedKbIds.value.splice(idx, 1)
  } else {
    selectedKbIds.value.push(kbId)
  }
  axios({ url: '/knowledge/conversation/kb/save', method: 'post', data: { conversationId: convId, kbIds: [...selectedKbIds.value] } })
    .catch(() => msg(langData.axiosRequestErr, 'error'))
}

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
          <span class="sp-value">{{ kbList.length > 0 ? langData.settingsPanel_mcpCount.replace('{count}', kbList.length) : langData.settingsPanel_noMcp }}</span>
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
        <div v-if="convStore.currentId" class="kb-select-section">
          <div class="kb-section-title">
            <Icon icon="lucide:book-open" class="kb-section-icon" />
            <span>{{ langData.settingsPanel_kbSelect }}</span>
          </div>
          <div v-if="convKbLoading" class="kb-loading">{{ langData.inputArea_loading }}</div>
          <div v-else-if="kbList.length === 0" class="kb-empty">{{ langData.knowledgeMgmt_noKb }}</div>
          <div v-else class="kb-checkbox-list">
            <div v-for="kb in kbList" :key="kb.id" class="kb-checkbox-item" @click="toggleKb(kb.id)">
              <span class="kb-checkbox-box" :class="{ checked: selectedKbIds.includes(kb.id) }">
                <Icon v-if="selectedKbIds.includes(kb.id)" icon="lucide:check" class="kb-check-icon" />
              </span>
              <span class="kb-checkbox-label">{{ kb.name }}</span>
            </div>
          </div>
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

.kb-select-section { padding: 12px 0; border-top: 1px solid var(--el-border-color); margin-top: 4px; }
.kb-section-title { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 10px; }
.kb-section-icon { color: var(--el-color-primary); font-size: 15px; }
.kb-loading { font-size: 12px; color: var(--el-text-color-placeholder); padding: 4px 0; }
.kb-empty { font-size: 12px; color: var(--el-text-color-placeholder); padding: 4px 0; }
.kb-checkbox-list { display: flex; flex-direction: column; gap: 2px; max-height: 160px; overflow-y: auto; }
.kb-checkbox-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--el-text-color-primary); transition: background 0.1s; }
.kb-checkbox-item:hover { background: var(--el-fill-color-light); }
.kb-checkbox-box { width: 16px; height: 16px; border-radius: 4px; border: 1.5px solid var(--el-border-color); display: flex; align-items: center; justify-content: center; flex-shrink: 0; transition: all 0.15s; }
.kb-checkbox-box.checked { background: var(--el-color-primary); border-color: var(--el-color-primary); }
.kb-check-icon { color: #fff; font-size: 12px; }
.kb-checkbox-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

html.dark .kb-checkbox-item:hover { background: var(--el-fill-color); }
</style>
