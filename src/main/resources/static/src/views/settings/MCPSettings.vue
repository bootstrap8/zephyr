<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import type { McpTool } from '@/types/chat'
import { getLangData } from '@/i18n/locale'

const store = useSettingsStore()
const langData = getLangData()

const showServerForm = ref(false)
const editServerId = ref<string | null>(null)
const serverName = ref('')
const serverTransport = ref<'stdio' | 'http'>('stdio')
const serverCommand = ref('')
const serverArgs = ref('')
const serverEnvVars = ref('')
const serverUrl = ref('')
const serverHeaders = ref('')

const expandedServerId = ref<string | null>(null)
const serverTools = ref<Record<string, McpTool[]>>({})
const loadingTools = ref<Record<string, boolean>>({})

const addingToolFor = ref<string | null>(null)
const newToolName = ref('')
const newToolDesc = ref('')

onMounted(async () => {
  await store.loadMcpServers()
  await store.loadMcpToolCount()
})

function openAddServer() {
  editServerId.value = null
  serverName.value = ''
  serverTransport.value = 'stdio'
  serverCommand.value = ''
  serverArgs.value = ''
  serverEnvVars.value = ''
  serverUrl.value = ''
  serverHeaders.value = ''
  showServerForm.value = true
}

function openEditServer(s: any) {
  editServerId.value = s.id
  serverName.value = s.name
  serverTransport.value = s.transport || 'stdio'
  serverCommand.value = s.command || ''
  serverArgs.value = s.args || ''
  serverEnvVars.value = s.envVars || ''
  serverUrl.value = s.url || ''
  serverHeaders.value = s.headers || ''
  showServerForm.value = true
}

async function saveServer() {
  if (!serverName.value.trim()) return
  const data: any = {
    name: serverName.value.trim(),
    transport: serverTransport.value,
    command: serverCommand.value.trim(),
    args: serverArgs.value.trim(),
    envVars: serverEnvVars.value.trim(),
    url: serverUrl.value.trim(),
    headers: serverHeaders.value.trim()
  }
  if (editServerId.value) {
    data.id = editServerId.value
    await store.updateMcpServer(data)
  } else {
    await store.createMcpServer(data)
  }
  showServerForm.value = false
}

async function deleteServer(id: string) {
  await store.deleteMcpServer(id)
  delete serverTools.value[id]
  if (expandedServerId.value === id) expandedServerId.value = null
}

async function connectServer(id: string) {
  await store.connectMcpServer(id)
  await toggleServerTools(id, true)
}

async function disconnectServer(id: string) {
  await store.disconnectMcpServer(id)
}

async function toggleServerTools(id: string, forceOpen = false) {
  if (expandedServerId.value === id && !forceOpen) {
    expandedServerId.value = null
    return
  }
  expandedServerId.value = id
  if (!serverTools.value[id] || forceOpen) {
    loadingTools.value[id] = true
    serverTools.value[id] = await store.loadMcpTools(id)
    loadingTools.value[id] = false
  }
}

async function addTool(serverId: string) {
  if (!newToolName.value.trim()) return
  const created = await store.createMcpTool(serverId, newToolName.value.trim(), newToolDesc.value.trim())
  if (created && serverTools.value[serverId]) {
    serverTools.value[serverId].push({
      id: created.id, serverId, toolName: created.toolName,
      description: created.description, enabled: true, source: 'manual'
    })
  }
  newToolName.value = ''
  newToolDesc.value = ''
  addingToolFor.value = null
  await store.loadMcpToolCount()
}

function confirmDeleteTool(toolId: string, serverId: string, toolName: string) {
  ElMessageBox.confirm(
    `确定要删除工具 "${toolName}" 吗？此操作不可恢复。`,
    '确认删除',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => deleteTool(toolId, serverId)).catch(() => {})
}

async function deleteTool(toolId: string, serverId: string) {
  await store.deleteMcpTool(toolId)
  if (serverTools.value[serverId])
    serverTools.value[serverId] = serverTools.value[serverId].filter(t => t.id !== toolId)
  await store.loadMcpToolCount()
}

async function toggleTool(toolId: string, enabled: boolean, serverId: string) {
  await store.toggleMcpTool(toolId, enabled)
  if (serverTools.value[serverId]) {
    const t = serverTools.value[serverId].find(x => x.id === toolId)
    if (t) t.enabled = enabled
  }
  await store.loadMcpToolCount()
}

function confirmDeleteServer(id: string, name: string) {
  ElMessageBox.confirm(
    langData.mcpMgmt_confirmDeleteMsg.replace('{name}', name),
    langData.mcpMgmt_confirmDeleteTitle,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => deleteServer(id)).catch(() => {})
}

function toolsCount(count: number): string {
  return langData.mcpMgmt_toolsCount.replace('{count}', String(count))
}
</script>

<template>
  <div class="mcp-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.mcpMgmt_title }}</h1>
      </div>
      <button v-if="store.mcpServers.length > 0" class="btn-primary" @click="openAddServer">
        <Icon icon="lucide:plus" /> {{ langData.mcpMgmt_addServer }}
      </button>
    </div>
    <p class="subtitle">{{ langData.mcpMgmt_subtitle }}</p>

    <div v-if="store.mcpServers.length === 0" class="empty-state">
      <Icon icon="lucide:server" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.mcpMgmt_noServer }}</h3>
      <p class="empty-desc">{{ langData.mcpMgmt_noServerDesc }}</p>
      <button class="btn-primary" @click="openAddServer">
        <Icon icon="lucide:plus" /> {{ langData.mcpMgmt_addFirstServer }}
      </button>
    </div>

    <div class="server-list">
      <div
        v-for="s in store.mcpServers"
        :key="s.id"
        class="server-card"
        :class="{ expanded: expandedServerId === s.id }"
      >
        <div class="card-main" @click="toggleServerTools(s.id!)">
          <div class="server-icon" :class="s.transport">
            <Icon :icon="s.transport === 'http' ? 'lucide:globe' : 'lucide:terminal'" width="18" />
          </div>
          <div class="server-info">
            <div class="server-name">{{ s.name }}</div>
            <div class="server-meta">
              <span class="badge badge-transport">{{ s.transport }}</span>
              <span class="badge badge-status" :class="'badge-' + s.status">
                <span class="status-dot" :class="s.status"></span>
                {{ s.status === 'connected' ? langData.mcpMgmt_connected : s.status === 'error' ? langData.mcpMgmt_connectFailed : langData.mcpMgmt_disconnected }}
              </span>
            </div>
            <div class="server-detail">
              <template v-if="s.transport === 'http'">{{ s.url }}</template>
              <template v-else>$ {{ s.command }} {{ s.args?.split('\n')[0] || '' }}</template>
            </div>
          </div>
          <div class="server-actions" @click.stop>
            <button class="btn-icon" @click="openEditServer(s)" :title="langData.btnEdit">
              <Icon icon="lucide:pencil" width="15" />
            </button>
            <button v-if="s.status === 'connected'" class="btn-icon" @click="disconnectServer(s.id!)" :title="langData.mcpMgmt_disconnectTooltip">
              <Icon icon="lucide:unplug" width="15" />
            </button>
            <button v-else class="btn-icon" @click="connectServer(s.id!)" :title="langData.mcpMgmt_connectTooltip">
              <Icon icon="lucide:plug" width="15" />
            </button>
            <button class="btn-icon" @click="confirmDeleteServer(s.id!, s.name)" :title="langData.btnDelete" style="color:var(--el-color-danger)">
              <Icon icon="lucide:trash-2" width="15" />
            </button>
            <Icon icon="lucide:chevron-down" class="expand-chevron" width="20" />
          </div>
        </div>

        <div class="tools-panel">
          <div v-if="loadingTools[s.id!]" class="tools-loading">{{ langData.inputArea_loading }}</div>
          <template v-else-if="serverTools[s.id!]?.length">
            <div class="tools-header">
              <span>{{ toolsCount(serverTools[s.id!].length) }}</span>
            </div>
            <div v-for="t in serverTools[s.id!]" :key="t.id" class="tool-row">
              <div class="tool-dot" :class="t.source"></div>
              <div class="tool-info">
                <span class="tool-name">{{ t.toolName }}</span>
                <span v-if="t.description" class="tool-desc">{{ t.description }}</span>
              </div>
              <span class="tool-source" :class="t.source">
                {{ t.source === 'discovered' ? langData.mcpMgmt_autoDiscovered : langData.mcpMgmt_manualAdd }}
              </span>
              <label class="toggle-switch">
                <input type="checkbox" :checked="t.enabled" @change="toggleTool(t.id!, ($event.target as HTMLInputElement).checked, s.id!)" />
                <span class="toggle-slider"></span>
              </label>
              <button class="btn-icon-sm" @click="confirmDeleteTool(t.id!, s.id!, t.toolName)" :title="langData.btnDelete">
                <Icon icon="lucide:x" width="13" />
              </button>
            </div>
          </template>
          <div v-else class="tools-empty">
            {{ s.status === 'connected' ? langData.mcpMgmt_noToolsConnected : langData.mcpMgmt_noToolsDisconnected }}
          </div>

          <div v-if="addingToolFor === s.id" class="add-tool-row">
            <input v-model="newToolName" :placeholder="langData.mcpMgmt_toolName" />
            <input v-model="newToolDesc" :placeholder="langData.mcpMgmt_toolDesc" />
            <button class="btn-primary btn-sm" @click="addTool(s.id!)">{{ langData.btnAdd }}</button>
            <button class="btn-secondary btn-sm" @click="addingToolFor = null">{{ langData.btnCancel }}</button>
          </div>
          <button v-else class="add-tool-btn" @click.stop="addingToolFor = s.id!; newToolName = ''; newToolDesc = ''">
            <Icon icon="lucide:plus" width="14" /> {{ langData.mcpMgmt_addToolManually }}
          </button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="showServerForm" class="modal-overlay" @click.self="showServerForm = false">
        <div class="modal">
          <div class="modal-header">
            <h2>{{ editServerId ? langData.mcpMgmt_editServer : langData.mcpMgmt_addMcpServer }}</h2>
            <button class="btn-icon" @click="showServerForm = false">
              <Icon icon="lucide:x" width="18" />
            </button>
          </div>
          <div class="modal-body">
            <div class="form-group">
              <label class="form-label">{{ langData.mcpMgmt_serverName }}</label>
              <input class="form-input" v-model="serverName" :placeholder="langData.mcpMgmt_serverNamePlaceholder" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ langData.mcpMgmt_transport }}</label>
              <div class="transport-toggle">
                <button :class="{ active: serverTransport === 'stdio' }" @click="serverTransport = 'stdio'">stdio</button>
                <button :class="{ active: serverTransport === 'http' }" @click="serverTransport = 'http'">HTTP</button>
              </div>
            </div>
            <template v-if="serverTransport === 'stdio'">
              <div class="form-group">
                <label class="form-label">{{ langData.mcpMgmt_startCommand }}</label>
                <input class="form-input" v-model="serverCommand" :placeholder="langData.mcpMgmt_startCommandPlaceholder" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ langData.mcpMgmt_args }}</label>
                <textarea class="form-textarea" v-model="serverArgs" :placeholder="langData.mcpMgmt_argsPlaceholder" rows="3"></textarea>
              </div>
              <div class="form-group">
                <label class="form-label">{{ langData.mcpMgmt_envVars }}</label>
                <textarea class="form-textarea" v-model="serverEnvVars" :placeholder="langData.mcpMgmt_envVarsPlaceholder" rows="2"></textarea>
              </div>
            </template>
            <template v-else>
              <div class="form-group">
                <label class="form-label">{{ langData.mcpMgmt_serverUrl }}</label>
                <input class="form-input" v-model="serverUrl" :placeholder="langData.mcpMgmt_serverUrlPlaceholder" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ langData.mcpMgmt_customHeaders }}</label>
                <textarea class="form-textarea" v-model="serverHeaders" :placeholder="langData.mcpMgmt_customHeadersPlaceholder" rows="2"></textarea>
              </div>
            </template>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showServerForm = false">{{ langData.btnCancel }}</button>
            <button class="btn-primary" @click="saveServer">{{ langData.btnSave }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.mcp-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-secondary:hover { background: var(--el-fill-color-light); }
.btn-sm { font-size: 12px; padding: 6px 14px; }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 360px; margin: 0 auto 24px; }

.server-list { display: flex; flex-direction: column; gap: 12px; }

.server-card { background: var(--el-fill-color-lighter); border-radius: 12px; overflow: hidden; transition: box-shadow 200ms; }
.server-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.card-main { display: flex; align-items: flex-start; gap: 16px; padding: 20px 24px; cursor: pointer; }
.server-icon { width: 40px; height: 40px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.server-icon.stdio { background: var(--el-text-color-primary); }
.server-icon.http { background: var(--el-color-primary); }

.server-info { flex: 1; min-width: 0; }
.server-name { font-size: 16px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 4px; }
.server-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.badge { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 500; padding: 2px 10px; border-radius: 99px; }
.badge-transport { background: var(--el-bg-color); color: var(--el-text-color-secondary); font-family: monospace; }
.badge-connected { color: var(--el-color-success); }
.badge-disconnected { color: var(--el-text-color-placeholder); }
.badge-error { color: var(--el-color-danger); }
.status-dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.status-dot.connected { background: var(--el-color-success); }
.status-dot.disconnected { background: var(--el-text-color-placeholder); }
.status-dot.error { background: var(--el-color-danger); }

.server-detail { font-size: 12px; color: var(--el-text-color-placeholder); font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.server-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.expand-chevron { color: var(--el-text-color-placeholder); transition: transform 250ms; flex-shrink: 0; }
.server-card.expanded .expand-chevron { transform: rotate(180deg); }

.btn-icon { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; transition: background 150ms; }
.btn-icon:hover { background: var(--el-fill-color-light); }
.btn-icon-sm { width: 26px; height: 26px; border-radius: 50%; border: none; background: transparent; cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-placeholder); flex-shrink: 0; }
.btn-icon-sm:hover { background: var(--el-fill-color-light); color: var(--el-color-danger); }

.tools-panel { display: none; border-top: 1px solid var(--el-border-color); padding: 20px 24px; }
.server-card.expanded .tools-panel { display: block; }
.tools-header { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); margin-bottom: 12px; }
.tools-loading, .tools-empty { font-size: 13px; color: var(--el-text-color-placeholder); padding: 16px 0; text-align: center; }

.tool-row { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
.tool-row + .tool-row { border-top: 1px solid var(--el-border-color-lighter); }
.tool-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--el-text-color-placeholder); flex-shrink: 0; }
.tool-dot.manual { background: var(--el-color-primary); }
.tool-info { flex: 1; min-width: 0; }
.tool-name { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); font-family: monospace; }
.tool-desc { display: block; font-size: 11px; color: var(--el-text-color-placeholder); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-source { font-size: 10px; padding: 2px 8px; border-radius: 99px; flex-shrink: 0; }
.tool-source.discovered { background: var(--el-fill-color-light); color: var(--el-text-color-secondary); }
.tool-source.manual { background: rgba(204,120,92,0.1); color: var(--el-color-primary); }

.toggle-switch { position: relative; width: 38px; height: 22px; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 99px; cursor: pointer; transition: background 200ms; }
.toggle-slider::after { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: transform 200ms; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::after { transform: translateX(16px); }

.add-tool-btn { display: flex; align-items: center; gap: 6px; margin-top: 12px; padding: 6px 12px; border-radius: 6px; border: 1px dashed var(--el-border-color); background: transparent; color: var(--el-color-primary); font-size: 12px; cursor: pointer; font-family: inherit; }
.add-tool-btn:hover { background: var(--el-fill-color-light); }
.add-tool-row { display: flex; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--el-border-color); }
.add-tool-row input { flex: 1; height: 34px; padding: 6px 10px; border-radius: 6px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 13px; color: var(--el-text-color-primary); outline: none; font-family: monospace; }
.add-tool-row input:focus { border-color: var(--el-color-primary); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.35); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.modal { background: var(--el-bg-color); border-radius: 16px; width: 520px; max-width: calc(100vw - 48px); max-height: 92vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,0.1); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 24px 28px 0; }
.modal-header h2 { font-family: Georgia, serif; font-size: 24px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin: 0; }
.modal-body { padding: 24px 28px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 0 28px 24px; }

.form-group { margin-bottom: 16px; }
.form-label { display: block; font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 6px; }
.form-input { width: 100%; height: 40px; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; box-sizing: border-box; }
.form-input:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }
.form-textarea { width: 100%; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 13px; color: var(--el-text-color-primary); outline: none; font-family: monospace; resize: vertical; box-sizing: border-box; line-height: 1.6; }
.form-textarea:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }

.transport-toggle { display: flex; background: var(--el-fill-color-lighter); border-radius: 8px; padding: 3px; width: fit-content; }
.transport-toggle button { padding: 7px 20px; border-radius: 6px; border: none; background: transparent; font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); cursor: pointer; font-family: monospace; transition: all 200ms; }
.transport-toggle button.active { background: var(--el-bg-color); color: var(--el-text-color-primary); box-shadow: 0 1px 3px rgba(0,0,0,0.06); }

@media (max-width: 640px) {
  .mcp-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
  .card-main { padding: 16px; }
}
</style>
