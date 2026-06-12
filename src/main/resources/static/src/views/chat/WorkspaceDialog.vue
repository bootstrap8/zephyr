<script lang="ts" setup>
import { ref } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { msg } from '@/utils/Utils'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()
const emit = defineEmits<{ close: [] }>()
const workspaceStore = useWorkspaceStore()
const name = ref('')
const path = ref('')
const saving = ref(false)

const showBrowser = ref(false)
const browserPath = ref('')
const dirs = ref<{ name: string; path: string }[]>([])
const browserLoading = ref(false)

function openBrowser() {
  showBrowser.value = true
  loadDirs(browserPath.value || '/')
}

function loadDirs(parent: string) {
  browserLoading.value = true
  axios({ url: '/workspace/browse', method: 'get', params: { parent } })
    .then(res => {
      if (res.data.state === 'OK') {
        dirs.value = res.data.body || []
        browserPath.value = parent
      }
    })
    .catch(() => {})
    .finally(() => { browserLoading.value = false })
}

function onDirClick(item: { name: string; path: string }) {
  if (item.name === '..') {
    loadDirs(item.path)
  } else {
    loadDirs(item.path)
  }
}

function selectCurrentDir() {
  path.value = browserPath.value
  if (!name.value.trim()) {
    const parts = browserPath.value.replace(/\/+$/, '').split('/')
    name.value = parts[parts.length - 1] || browserPath.value
  }
  showBrowser.value = false
}

function onSubmit() {
  if (!path.value.trim()) { msg(langData.workspaceDialog_pathRequired, 'warning'); return }
  saving.value = true
  axios({
    url: '/workspace/create',
    method: 'post',
    data: { name: name.value.trim() || undefined, path: path.value.trim() }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        workspaceStore.addWorkspace(res.data.body)
        emit('close')
      } else {
        msg(res.data.errorMessage, 'warning')
      }
    })
    .catch(err => msg(err?.response?.data?.errorMessage, 'error'))
    .finally(() => { saving.value = false })
}
</script>

<template>
  <Teleport to="body">
    <div class="ws-dialog-overlay" @click="emit('close')"></div>
    <div class="ws-dialog">
      <div class="ws-dialog-header">
        <span>{{ langData.workspaceDialog_title }}</span>
        <button class="ws-dialog-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="ws-dialog-body">
        <label class="ws-field">
          <span>{{ langData.workspaceDialog_name }}</span>
          <input v-model="name" class="ws-input" :placeholder="langData.workspaceDialog_namePlaceholder" @keydown.enter="onSubmit" />
        </label>
        <label class="ws-field">
          <span>{{ langData.workspaceDialog_directory }}</span>
          <div class="ws-dir-row">
            <input v-model="path" class="ws-input ws-dir-input" placeholder="/Users/hbq/my-project" @keydown.enter="onSubmit" />
            <button class="ws-btn ws-btn-browse" @click="openBrowser">{{ langData.workspaceDialog_browse }}</button>
          </div>
        </label>

        <!-- 目录浏览器 -->
        <div v-if="showBrowser" class="ws-browser">
          <div class="ws-browser-head">
            <Icon icon="lucide:folder-open" class="ws-browser-icon" />
            <span class="ws-browser-path">{{ browserPath }}</span>
            <button class="ws-btn ws-btn-select" @click="selectCurrentDir">{{ langData.workspaceDialog_selectDir }}</button>
          </div>
          <div v-if="browserLoading" class="ws-browser-loading">{{ langData.inputArea_loading }}</div>
          <div v-else class="ws-browser-list">
            <div v-for="d in dirs" :key="d.path" class="ws-browser-item" @click="onDirClick(d)">
              <Icon :icon="d.name === '..' ? 'lucide:corner-up-left' : 'lucide:folder'" class="ws-browser-item-icon" />
              <span>{{ d.name }}</span>
            </div>
          </div>
        </div>
      </div>
      <div class="ws-dialog-footer">
        <button class="ws-btn ws-btn-cancel" @click="emit('close')">{{ langData.btnCancel }}</button>
        <button class="ws-btn ws-btn-confirm" :disabled="saving" @click="onSubmit">
          {{ saving ? langData.workspaceDialog_creating : langData.workspaceDialog_create }}
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ws-dialog-overlay { position: fixed; inset: 0; z-index: 1000; background: rgba(0,0,0,0.2); backdrop-filter: blur(2px); }
.ws-dialog { position: fixed; top: 30%; left: 50%; transform: translate(-50%, -50%); width: 420px; max-width: 90vw; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; box-shadow: 0 12px 48px rgba(0,0,0,0.12); z-index: 1001; }
.ws-dialog-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 0; font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); }
.ws-dialog-close { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.ws-dialog-close:hover { background: var(--el-fill-color-light); }
.ws-dialog-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.ws-field { display: flex; flex-direction: column; gap: 4px; }
.ws-field span { font-size: 13px; color: var(--el-text-color-secondary); }
.ws-input { width: 100%; padding: 8px 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; outline: none; font-family: inherit; box-sizing: border-box; }
.ws-input:focus { border-color: var(--el-color-primary); }
.ws-input::placeholder { color: var(--el-text-color-placeholder); }
.ws-dir-row { display: flex; gap: 8px; }
.ws-dir-input { flex: 1; }

.ws-browser { border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden; max-height: 220px; display: flex; flex-direction: column; }
.ws-browser-head { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-bottom: 1px solid var(--el-border-color); background: var(--el-fill-color-light); font-size: 12px; color: var(--el-text-color-secondary); }
.ws-browser-icon { font-size: 14px; color: var(--el-color-primary); flex-shrink: 0; }
.ws-browser-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.ws-browser-loading { padding: 20px; text-align: center; font-size: 12px; color: var(--el-text-color-placeholder); }
.ws-browser-list { flex: 1; overflow-y: auto; padding: 4px; }
.ws-browser-list::-webkit-scrollbar { width: 2px; }
.ws-browser-list::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 1px; }
.ws-browser-item { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--el-text-color-primary); transition: background 0.1s; }
.ws-browser-item:hover { background: var(--el-fill-color-light); }
.ws-browser-item-icon { font-size: 15px; color: var(--el-text-color-secondary); flex-shrink: 0; }

.ws-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 0 20px 16px; }
.ws-btn { padding: 7px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); font-size: 13px; cursor: pointer; transition: background 0.15s; }
.ws-btn-cancel { background: var(--el-bg-color); color: var(--el-text-color-regular); }
.ws-btn-cancel:hover { background: var(--el-fill-color-light); }
.ws-btn-browse { background: var(--el-bg-color); color: var(--el-text-color-regular); padding: 8px 12px; white-space: nowrap; }
.ws-btn-browse:hover { background: var(--el-fill-color-light); }
.ws-btn-select { padding: 3px 10px; font-size: 12px; white-space: nowrap; background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-select:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm { background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-confirm:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm:disabled { opacity: 0.6; cursor: default; }
</style>
