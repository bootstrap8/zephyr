<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import axios from '@/network'
import { msg } from '@/utils/Utils'
import { getLangData } from '@/i18n/locale'
import type { Workspace } from '@/types/chat'

const workspaceStore = useWorkspaceStore()
const langData = getLangData()
const loading = ref(true)

onMounted(async () => {
  loading.value = true
  axios({ url: '/workspace/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        workspaceStore.setWorkspaces(res.data.body || [])
      }
    })
    .catch(() => {})
    .finally(() => { loading.value = false })
})

function formatTime(ts: number): string {
  if (!ts) return ''
  return new Date(ts * 1000).toLocaleString()
}

function confirmDelete(ws: Workspace) {
  ElMessageBox.confirm(
    langData.workspaceMgmt_confirmDeleteMsg.replace('{name}', ws.name),
    langData.confirmDelete,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => doDelete(ws.id)).catch(() => {})
}

function doDelete(id: string) {
  axios({ url: '/workspace/delete', method: 'post', data: { id } })
    .then(res => {
      if (res.data.state === 'OK') {
        workspaceStore.removeWorkspace(id)
      } else {
        msg(res.data.errorMessage, 'warning')
      }
    })
    .catch(err => msg(err?.response?.data?.errorMessage, 'error'))
}
</script>

<template>
  <div class="ws-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.workspaceMgmt_title }}</h1>
      </div>
    </div>
    <p class="subtitle">{{ langData.workspaceMgmt_subtitle }}</p>

    <div v-if="loading" class="loading-state">
      {{ langData.inputArea_loading }}
    </div>

    <div v-else-if="workspaceStore.workspaces.length === 0" class="empty-state">
      <Icon icon="lucide:folder-open" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.workspaceMgmt_noWorkspace }}</h3>
      <p class="empty-desc">{{ langData.workspaceMgmt_noWorkspaceDesc }}</p>
    </div>

    <div v-else class="ws-list">
      <div v-for="ws in workspaceStore.workspaces" :key="ws.id" class="ws-card">
        <div class="ws-card-main">
          <div class="ws-icon">
            <Icon icon="lucide:folder" width="18" />
          </div>
          <div class="ws-info">
            <div class="ws-name">{{ ws.name }}</div>
            <div class="ws-path">{{ ws.path }}</div>
            <div class="ws-meta">{{ langData.tableHeaderCreateTime }}: {{ formatTime(ws.createdAt) }}</div>
          </div>
          <div class="ws-actions">
            <button
              v-if="ws.isSystem !== 1"
              class="btn-icon"
              @click="confirmDelete(ws)"
              :title="langData.btnDelete"
              style="color:var(--el-color-danger)"
            >
              <Icon icon="lucide:trash-2" width="15" />
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ws-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

.loading-state { text-align: center; padding: 80px 24px; font-size: 14px; color: var(--el-text-color-placeholder); }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 360px; margin: 0 auto 24px; }

.ws-list { display: flex; flex-direction: column; gap: 12px; }

.ws-card { background: var(--el-fill-color-lighter); border-radius: 12px; overflow: hidden; }
.ws-card-main { display: flex; align-items: flex-start; gap: 16px; padding: 20px 24px; }

.ws-icon { width: 40px; height: 40px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; background: var(--el-color-primary); color: #fff; }

.ws-info { flex: 1; min-width: 0; }
.ws-name { font-size: 16px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 4px; }
.ws-path { font-size: 12px; color: var(--el-text-color-placeholder); font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 4px; }
.ws-meta { font-size: 12px; color: var(--el-text-color-secondary); }

.ws-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }

.btn-icon { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; transition: background 150ms; }
.btn-icon:hover { background: var(--el-fill-color-light); }

@media (max-width: 640px) {
  .ws-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
  .ws-card-main { padding: 16px; }
}
</style>
