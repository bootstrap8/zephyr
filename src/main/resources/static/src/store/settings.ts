import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ModelConfig, McpServer, McpTool, SkillConfig, MemoryItem } from '@/types/chat'
import axios from '@/network'

export const useSettingsStore = defineStore('settings', () => {
  const currentModel = ref('DeepSeek-V3')
  const models = ref<ModelConfig[]>([
    { name: 'DeepSeek-V3', isDefault: true },
    { name: 'Claude Opus 4.7', isDefault: false },
    { name: 'Claude Sonnet 4.6', isDefault: false },
    { name: 'GPT-4o', isDefault: false }
  ])
  const mcpServers = ref<McpServer[]>([])
  const mcpToolCount = ref(0)
  const skills = ref<SkillConfig[]>([])
  const memories = ref<MemoryItem[]>([])
  const contextUsed = ref(53248)

  const contextTotal = computed(() => {
    const def = models.value.find(m => m.name === currentModel.value)
    return def?.maxContextTokens || 131072
  })

  const contextPercent = computed(() =>
    Math.round((contextUsed.value / contextTotal.value) * 100)
  )

  function setModel(name: string) { currentModel.value = name }
  function addModel(model: ModelConfig) { models.value.push(model) }

  // === Model API 方法 ===

  async function loadModels() {
    try {
      const res = await axios({ url: '/model-config/list', method: 'get' })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        const list: ModelConfig[] = res.data.body.map((m: any) => ({
          id: m.id,
          name: m.name,
          baseUrl: m.baseUrl,
          isDefault: m.isDefault === 1,
          apiKey: m.apiKeyEncrypted,
          maxContextTokens: m.maxContextTokens
        }))
        models.value = list
        const def = list.find((m: ModelConfig) => m.isDefault)
        currentModel.value = def ? def.name : (list.length > 0 ? list[0].name : '无')
      }
    } catch (_) { /* keep defaults */ }
  }

  async function addModelRemote(name: string, baseUrl: string, apiKey: string) {
    const res = await axios({ url: '/model-config/create', method: 'post', data: { name, baseUrl, apiKey } })
    if (res.data.state === 'OK') await loadModels()
  }

  async function updateModelRemote(id: string, name: string, baseUrl: string, apiKey: string) {
    await axios({ url: '/model-config/update', method: 'post', data: { id, name, baseUrl, apiKey } })
    await loadModels()
  }

  async function deleteModelRemote(id: string) {
    await axios({ url: '/model-config/delete', method: 'post', data: { id } })
    await loadModels()
  }

  async function setDefaultModelRemote(id: string) {
    await axios({ url: '/model-config/set-default', method: 'post', data: { id } })
    await loadModels()
  }

  // === MCP API 方法 ===

  async function loadMcpServers() {
    try {
      const res = await axios({ url: '/mcp/server/list', method: 'get' })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        mcpServers.value = res.data.body.map((s: any) => ({
          id: s.id, name: s.name, transport: s.transport,
          command: s.command, args: s.args, envVars: s.envVars,
          url: s.url, headers: s.headers, status: s.status,
          createdAt: s.createdAt, updatedAt: s.updatedAt
        }))
      }
    } catch (_) {}
  }

  async function loadMcpTools(serverId: string) {
    try {
      const res = await axios({ url: '/mcp/tool/list', method: 'get', params: { serverId } })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        return res.data.body.map((t: any) => ({
          id: t.id, serverId: t.serverId, toolName: t.toolName,
          description: t.description, enabled: t.enabled === 1,
          source: t.source, createdAt: t.createdAt
        })) as McpTool[]
      }
    } catch (_) {}
    return []
  }

  async function loadMcpToolCount() {
    try {
      const res = await axios({ url: '/mcp/tool/count', method: 'get' })
      if (res.data.state === 'OK') mcpToolCount.value = res.data.body
    } catch (_) {}
  }

  async function createMcpServer(data: Partial<McpServer>) {
    const res = await axios({ url: '/mcp/server/create', method: 'post', data })
    if (res.data.state === 'OK') await loadMcpServers()
  }

  async function updateMcpServer(data: Partial<McpServer>) {
    await axios({ url: '/mcp/server/update', method: 'post', data })
    await loadMcpServers()
  }

  async function deleteMcpServer(id: string) {
    await axios({ url: '/mcp/server/delete', method: 'post', data: { id } })
    await loadMcpServers()
  }

  async function connectMcpServer(id: string) {
    await axios({ url: '/mcp/server/connect', method: 'post', data: { id } })
    await loadMcpServers()
  }

  async function disconnectMcpServer(id: string) {
    await axios({ url: '/mcp/server/disconnect', method: 'post', data: { id } })
    await loadMcpServers()
  }

  async function createMcpTool(serverId: string, toolName: string, description: string) {
    const res = await axios({ url: '/mcp/tool/create', method: 'post', data: { serverId, toolName, description } })
    if (res.data.state === 'OK') return res.data.body
  }

  async function deleteMcpTool(id: string) {
    await axios({ url: '/mcp/tool/delete', method: 'post', data: { id } })
  }

  async function toggleMcpTool(id: string, enabled: boolean) {
    await axios({ url: '/mcp/tool/toggle', method: 'post', data: { id, enabled: enabled ? 1 : 0 } })
  }

  // === Skill API 方法 ===

  async function loadSkills() {
    try {
      const res = await axios({ url: '/skill/list', method: 'get' })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        skills.value = res.data.body.map((s: any) => ({
          id: s.id, skillName: s.skillName, displayName: s.displayName,
          description: s.description, source: s.source, sourceUrl: s.sourceUrl,
          version: s.version, enabled: s.enabled, installPath: s.installPath,
          createdAt: s.createdAt, updatedAt: s.updatedAt
        }))
      }
    } catch (_) {}
  }

  async function installSkill(data: Record<string, string>) {
    const res = await axios({ url: '/skill/install', method: 'post', data })
    if (res.data.state === 'OK') await loadSkills()
    return res.data
  }

  async function uploadSkill(file: File) {
    const formData = new FormData()
    formData.append('file', file)
    const res = await axios({
      url: '/skill/upload', method: 'post',
      data: formData,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (res.data.state === 'OK') await loadSkills()
    return res.data
  }

  async function uninstallSkill(id: string) {
    await axios({ url: '/skill/uninstall', method: 'post', data: { id } })
    await loadSkills()
  }

  async function toggleSkill(id: string, enabled: boolean) {
    await axios({ url: '/skill/toggle', method: 'post', data: { id, enabled: enabled ? 1 : 0 } })
    await loadSkills()
  }

  async function syncScanSkills() {
    const res = await axios({ url: '/skill/sync-scan', method: 'get' })
    if (res.data.state === 'OK') return res.data.body as SkillConfig[]
    return []
  }

  async function syncInstallSkills(platform: string, skillNames: string[]) {
    const res = await axios({
      url: '/skill/sync-install', method: 'post',
      data: { platform, skillNames: skillNames.join(',') }
    })
    if (res.data.state === 'OK') await loadSkills()
    return res.data
  }

  // === Memory API 方法 ===

  async function loadMemories(type?: string) {
    try {
      const params = type ? { type } : {}
      const res = await axios({ url: '/memory/list', method: 'get', params })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        memories.value = res.data.body.map((m: any) => ({
          name: m.name,
          type: m.type,
          description: m.description,
          createdAt: m.createdAt,
          updatedAt: m.updatedAt
        }))
      }
    } catch (_) {}
  }

  async function loadMemoryDetail(name: string): Promise<MemoryItem | null> {
    try {
      const res = await axios({ url: '/memory/detail', method: 'get', params: { name } })
      if (res.data.state === 'OK') {
        const m = res.data.body
        return { name: m.name, type: m.type, description: m.description, content: m.content, createdAt: m.createdAt, updatedAt: m.updatedAt }
      }
    } catch (_) {}
    return null
  }

  async function createMemory(name: string, type: string, content: string) {
    const res = await axios({ url: '/memory/create', method: 'post', data: { name, type, content } })
    if (res.data.state === 'OK') return true
    return false
  }

  async function updateMemory(oldName: string, name: string, type: string, content: string) {
    const res = await axios({ url: '/memory/update', method: 'post', data: { oldName, name, type, content } })
    if (res.data.state === 'OK') return true
    return false
  }

  async function deleteMemories(names: string[]) {
    const res = await axios({ url: '/memory/delete', method: 'post', data: { names: names.join(',') } })
    if (res.data.state === 'OK') return true
    return false
  }

  return {
    currentModel, models, mcpServers, mcpToolCount, skills, memories,
    contextUsed, contextTotal, contextPercent,
    setModel, addModel,
    loadModels, addModelRemote, updateModelRemote, deleteModelRemote, setDefaultModelRemote,
    loadMcpServers, createMcpServer, updateMcpServer, deleteMcpServer,
    connectMcpServer, disconnectMcpServer,
    loadMcpTools, createMcpTool, deleteMcpTool, toggleMcpTool, loadMcpToolCount,
    loadSkills, installSkill, uploadSkill, uninstallSkill, toggleSkill,
    syncScanSkills, syncInstallSkills,
    loadMemories, loadMemoryDetail, createMemory, updateMemory, deleteMemories
  }
})
