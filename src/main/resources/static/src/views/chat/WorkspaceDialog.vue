<script lang="ts" setup>
import { ref } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { msg } from '@/utils/Utils'

const emit = defineEmits<{ close: [] }>()
const workspaceStore = useWorkspaceStore()
const name = ref('')
const path = ref('')
const saving = ref(false)

function onSubmit() {
  if (!path.value.trim()) { msg('请填写目录路径', 'warning'); return }
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
        <span>新建工作空间</span>
        <button class="ws-dialog-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="ws-dialog-body">
        <label class="ws-field">
          <span>名称</span>
          <input v-model="name" class="ws-input" placeholder="选填，默认取目录最后一级名" @keydown.enter="onSubmit" />
        </label>
        <label class="ws-field">
          <span>目录</span>
          <input v-model="path" class="ws-input" placeholder="/Users/hbq/my-project" @keydown.enter="onSubmit" />
        </label>
      </div>
      <div class="ws-dialog-footer">
        <button class="ws-btn ws-btn-cancel" @click="emit('close')">取消</button>
        <button class="ws-btn ws-btn-confirm" :disabled="saving" @click="onSubmit">
          {{ saving ? '创建中...' : '创建' }}
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
.ws-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 0 20px 16px; }
.ws-btn { padding: 7px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); font-size: 13px; cursor: pointer; transition: background 0.15s; }
.ws-btn-cancel { background: var(--el-bg-color); color: var(--el-text-color-regular); }
.ws-btn-cancel:hover { background: var(--el-fill-color-light); }
.ws-btn-confirm { background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-confirm:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm:disabled { opacity: 0.6; cursor: default; }
</style>
