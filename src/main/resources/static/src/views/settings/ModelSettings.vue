<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const settingsStore = useSettingsStore()
const showForm = ref(false)
const name = ref('')
const baseUrl = ref('')
const apiKey = ref('')
const maxCtx = ref('')
const editId = ref<string | null>(null)

onMounted(() => { settingsStore.loadModels() })

async function add() {
  if (!name.value.trim()) return
  if (editId.value) {
    await settingsStore.updateModelRemote(editId.value, name.value.trim(), baseUrl.value.trim(), apiKey.value, maxCtx.value)
  } else {
    await settingsStore.addModelRemote(name.value.trim(), baseUrl.value.trim(), apiKey.value, maxCtx.value)
  }
  name.value = ''
  baseUrl.value = ''
  apiKey.value = ''
  maxCtx.value = ''
  editId.value = null
  showForm.value = false
}

function startEdit(m: any) {
  editId.value = m.id
  name.value = m.name
  baseUrl.value = m.baseUrl || ''
  apiKey.value = ''
  maxCtx.value = m.maxContextTokens ? String(m.maxContextTokens) : ''
  showForm.value = true
}

function cancelForm() {
  name.value = ''
  baseUrl.value = ''
  apiKey.value = ''
  maxCtx.value = ''
  editId.value = null
  showForm.value = false
}

async function removeModel(id: string) {
  await settingsStore.deleteModelRemote(id)
}

async function setDefault(id: string) {
  await settingsStore.setDefaultModelRemote(id)
}

async function onSetCurrent(name: string) {
  settingsStore.setModel(name)
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) await settingsStore.setDefaultModelRemote(m.id)
}
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/chat')"><Icon icon="lucide:chevron-left" /></button>
      <h2>模型配置</h2>
    </div>
    <div class="page-body">
      <div v-for="m in settingsStore.models" :key="m.name" class="setting-row">
        <div class="row-left">
          <Icon icon="lucide:cpu" class="row-icon" />
          <div>
            <div class="row-title">{{ m.name }}</div>
            <div v-if="m.baseUrl" class="row-sub">{{ m.baseUrl }}</div>
          </div>
        </div>
        <div class="row-right">
          <button class="action-icon" @click="startEdit(m)" title="编辑"><Icon icon="lucide:pencil" /></button>
          <button class="action-icon danger" @click="m.id && removeModel(m.id)" title="删除"><Icon icon="lucide:trash-2" /></button>
          <button v-if="settingsStore.currentModel !== m.name" class="set-btn" @click="onSetCurrent(m.name)">使用</button>
          <span v-else class="current-badge">当前</span>
        </div>
      </div>

      <div v-if="showForm" class="add-form">
        <input v-model="name" placeholder="模型名称" />
        <input v-model="baseUrl" placeholder="Base URL（可选）" />
        <input v-model="apiKey" placeholder="API Key" type="password" />
        <input v-model="maxCtx" placeholder="最大上下文 (tokens, 可选，自动探测失败时填写)" />
        <div class="form-actions">
          <button class="btn btn-sec" @click="cancelForm">取消</button>
          <button class="btn btn-pri" @click="add">{{ editId ? '保存' : '添加' }}</button>
        </div>
      </div>
      <button v-else class="add-btn" @click="showForm = true"><Icon icon="lucide:plus" />添加模型</button>
    </div>
  </div>
</template>

<style scoped>
.settings-page { max-width: 680px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }
.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 12px; border-bottom: 1px solid var(--el-border-color); }
.row-left { display: flex; align-items: center; gap: 10px; }
.row-icon { color: var(--el-text-color-secondary); font-size: 16px; }
.row-title { font-size: 14px; color: var(--el-text-color-primary); }
.row-sub { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.row-right { display: flex; align-items: center; gap: 6px; }
.action-icon { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 14px; transition: all 0.15s; }
.action-icon:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.action-icon.danger:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
.set-btn { padding: 4px 12px; border-radius: 6px; border: 1px solid var(--el-color-primary); background: transparent; color: var(--el-color-primary); cursor: pointer; font-size: 12px; font-family: inherit; }
.set-btn:hover { background: rgba(204,120,92,0.08); }
.current-badge { font-size: 12px; padding: 3px 10px; border-radius: 99px; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.add-btn { display: flex; align-items: center; gap: 6px; margin-top: 16px; padding: 8px 14px; border-radius: 8px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; width: 100%; justify-content: center; }
.add-btn:hover { background: var(--el-fill-color-light); }
.add-form { margin-top: 16px; display: flex; flex-direction: column; gap: 8px; }
.add-form input { padding: 8px 12px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; }
.add-form input:focus { border-color: var(--el-color-primary); }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 6px 16px; border-radius: 8px; border: none; cursor: pointer; font-size: 13px; font-family: inherit; font-weight: 500; }
.btn-sec { background: var(--el-fill-color); color: var(--el-text-color-primary); }
.btn-pri { background: var(--el-color-primary); color: #fff; }
</style>
