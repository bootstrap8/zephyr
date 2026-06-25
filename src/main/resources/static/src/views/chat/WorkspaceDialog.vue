<script lang="ts" setup>
import { ref } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { msg } from '@/utils/Utils'
import { getLangData } from '@/i18n/locale'
import TreeNode from './TreeNode.vue'
import type { DirNode } from './TreeNode.vue'

const langData = getLangData()
const emit = defineEmits<{ close: [] }>()
const workspaceStore = useWorkspaceStore()
const name = ref('')
const path = ref('')
const saving = ref(false)

const showBrowser = ref(false)
const treeRoot = ref<{ name: string; path: string; children: DirNode[]; expanded: boolean; loaded: boolean } | null>(null)
const browserLoading = ref(false)
const rootCreating = ref(false)
const rootNewDirName = ref('')
const rootCreatingError = ref('')

function toDirNode(d: any): DirNode {
  return {
    name: d.name,
    path: d.path,
    children: [],
    expanded: false,
    loaded: false,
    loading: false,
    creating: false,
    newDirName: '',
    creatingError: ''
  } as DirNode
}

function openBrowser() {
  showBrowser.value = true
  loadRoot()
}

function loadRoot() {
  browserLoading.value = true
  axios({ url: '/workspace/browse', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const body = res.data.body || []
        const currentEntry = body.find((d: any) => d.name === '.')
        const rootPath = currentEntry ? currentEntry.path : '/'
        const items = body.filter((d: any) => d.name !== '.' && d.name !== '..').map(toDirNode)
        treeRoot.value = { name: rootPath, path: rootPath, children: items, expanded: true, loaded: true }
      }
    })
    .catch(() => msg('加载目录失败', 'error'))
    .finally(() => { browserLoading.value = false })
}

function loadChildren(node: DirNode) {
  if (node.loading) return
  node.loading = true
  axios({ url: '/workspace/browse', method: 'get', params: { parent: node.path } })
    .then(res => {
      if (res.data.state === 'OK') {
        node.children = (res.data.body || []).filter((d: any) => d.name !== '.' && d.name !== '..').map(toDirNode)
        node.loaded = true
      }
    })
    .catch(() => msg('加载目录失败', 'error'))
    .finally(() => { node.loading = false })
}

function startRootCreate() {
  rootCreating.value = true
  rootNewDirName.value = ''
  rootCreatingError.value = ''
}

function cancelRootCreate() {
  rootCreating.value = false
  rootNewDirName.value = ''
  rootCreatingError.value = ''
}

function confirmRootCreate() {
  const dirName = rootNewDirName.value.trim()
  if (!dirName) {
    rootCreatingError.value = '目录名不能为空'
    return
  }
  axios({
    url: '/workspace/mkdir',
    method: 'post',
    data: { parent: treeRoot.value!.path, name: dirName }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        rootCreating.value = false
        rootNewDirName.value = ''
        rootCreatingError.value = ''
        loadRoot()
      } else {
        rootCreatingError.value = res.data.errorMessage || '创建失败'
      }
    })
    .catch(err => {
      rootCreatingError.value = err?.response?.data?.errorMessage || '创建失败'
    })
}

function ensureExpanded(node: DirNode) {
  if (!node.loaded && !node.loading) {
    loadChildren(node)
  }
  node.expanded = true
}

function onToggle(node: DirNode) {
  if (node.expanded) {
    node.expanded = false
  } else {
    ensureExpanded(node)
  }
}

function onSelect(node: DirNode) {
  path.value = node.path
  if (!name.value.trim()) {
    const parts = node.path.replace(/\/+$/, '').split('/')
    name.value = parts[parts.length - 1] || node.path
  }
}

function onStartCreate(node: DirNode) {
  if (!node.expanded) {
    ensureExpanded(node)
  }
  node.creating = true
  node.newDirName = ''
  node.creatingError = ''
}

function onCancelCreate(node: DirNode) {
  node.creating = false
  node.newDirName = ''
  node.creatingError = ''
}

function onConfirmCreate(node: DirNode) {
  const dirName = node.newDirName.trim()
  if (!dirName) {
    node.creatingError = '目录名不能为空'
    return
  }
  axios({
    url: '/workspace/mkdir',
    method: 'post',
    data: { parent: node.path, name: dirName }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        node.creating = false
        node.newDirName = ''
        node.creatingError = ''
        loadChildren(node)
      } else {
        node.creatingError = res.data.errorMessage || '创建失败'
      }
    })
    .catch(err => {
      node.creatingError = err?.response?.data?.errorMessage || '创建失败'
    })
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
            <input v-model="path" class="ws-input ws-dir-input" :placeholder="langData.workspaceDialog_dirPlaceholder" readonly @click="openBrowser" />
            <button class="ws-btn ws-btn-browse" @click="openBrowser">{{ langData.workspaceDialog_browse }}</button>
          </div>
        </label>

        <div v-if="showBrowser" class="ws-browser">
          <div class="ws-browser-head">
            <Icon icon="lucide:folder-open" class="ws-browser-icon" />
            <span class="ws-browser-path">{{ treeRoot?.path || '' }}</span>
            <button class="ws-browser-head-add" title="新建目录" @click="startRootCreate">
              <Icon icon="lucide:plus" width="14" />
            </button>
          </div>
          <div v-if="rootCreating" class="tn-create" style="padding:4px 8px;border-bottom:1px solid var(--el-border-color)">
            <input v-model="rootNewDirName" class="tn-create-input" placeholder="目录名"
              @keydown.enter="confirmRootCreate" @keydown.escape="cancelRootCreate" />
            <button class="tn-create-btn tn-create-ok" @click="confirmRootCreate">
              <Icon icon="lucide:check" width="14" />
            </button>
            <button class="tn-create-btn tn-create-cancel" @click="cancelRootCreate">
              <Icon icon="lucide:x" width="14" />
            </button>
            <span v-if="rootCreatingError" class="tn-create-error">{{ rootCreatingError }}</span>
          </div>
          <div v-if="browserLoading" class="ws-browser-loading">{{ langData.inputArea_loading }}</div>
          <div v-else class="ws-browser-tree">
            <TreeNode
              v-for="node in treeRoot?.children || []"
              :key="node.path"
              :node="node"
              :depth="0"
              @toggle="onToggle"
              @select="onSelect"
              @start-create="onStartCreate"
              @cancel-create="onCancelCreate"
              @confirm-create="onConfirmCreate"
            />
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
.ws-dialog { position: fixed; top: 30%; left: 50%; transform: translate(-50%, -50%); width: 480px; max-width: 90vw; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; box-shadow: 0 12px 48px rgba(0,0,0,0.12); z-index: 1001; }
.ws-dialog-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 0; font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); }
.ws-dialog-close { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.ws-dialog-close:hover { background: var(--el-fill-color-light); }
.ws-dialog-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.ws-field { display: flex; flex-direction: column; gap: 4px; }
.ws-field span { font-size: 13px; color: var(--el-text-color-secondary); }
.ws-input { width: 100%; padding: 8px 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; outline: none; font-family: inherit; box-sizing: border-box; }
.ws-input:focus { border-color: var(--el-color-primary); }
.ws-input::placeholder { color: var(--el-text-color-placeholder); }
.ws-input[readonly] { cursor: pointer; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); }
.ws-dir-row { display: flex; gap: 8px; }
.ws-dir-input { flex: 1; }

.ws-browser { border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden; max-height: 320px; display: flex; flex-direction: column; }
.ws-browser-head { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-bottom: 1px solid var(--el-border-color); background: var(--el-fill-color-light); font-size: 12px; color: var(--el-text-color-secondary); }
.ws-browser-icon { font-size: 14px; color: var(--el-color-primary); flex-shrink: 0; }
.ws-browser-path { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.ws-browser-head-add { width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; border-radius: 4px; flex-shrink: 0; padding: 0; }
.ws-browser-head-add:hover { background: var(--el-fill-color); color: var(--el-color-primary); }
.ws-browser-loading { padding: 20px; text-align: center; font-size: 12px; color: var(--el-text-color-placeholder); }
.ws-browser-tree { flex: 1; overflow-y: auto; padding: 4px 8px; }
.ws-browser-tree::-webkit-scrollbar { width: 4px; }
.ws-browser-tree::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 2px; }

.ws-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 0 20px 16px; }
.ws-btn { padding: 7px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); font-size: 13px; cursor: pointer; transition: background 0.15s; }
.ws-btn-cancel { background: var(--el-bg-color); color: var(--el-text-color-regular); }
.ws-btn-cancel:hover { background: var(--el-fill-color-light); }
.ws-btn-browse { background: var(--el-bg-color); color: var(--el-text-color-regular); padding: 8px 12px; white-space: nowrap; }
.ws-btn-browse:hover { background: var(--el-fill-color-light); }
.ws-btn-confirm { background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-confirm:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm:disabled { opacity: 0.6; cursor: default; }

.tn-create { display: flex; align-items: center; gap: 4px; padding: 4px 0; }
.tn-create-input { flex: 1; padding: 4px 8px; border: 1px solid var(--el-color-primary); border-radius: 4px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 12px; outline: none; font-family: inherit; }
.tn-create-btn { width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border: none; border-radius: 4px; cursor: pointer; flex-shrink: 0; }
.tn-create-ok { background: var(--el-color-primary); color: #fff; }
.tn-create-ok:hover { background: var(--el-color-primary-dark-2); }
.tn-create-cancel { background: transparent; color: var(--el-text-color-secondary); }
.tn-create-cancel:hover { background: var(--el-fill-color-light); }
.tn-create-error { font-size: 11px; color: var(--el-color-danger); white-space: nowrap; }
</style>
