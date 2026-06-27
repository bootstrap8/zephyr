// === Chat Mode ===
export type ChatMode = 'default' | 'acceptEdits' | 'bypass'

// === SSE Chat Event ===
export interface ChatEvent {
  type: 'token' | 'thinking' | 'tool_call' | 'tool_result' | 'usage' | 'compaction' | 'done' | 'error' | 'clear'
  content?: string
  toolName?: string
  toolInput?: Record<string, unknown>
  toolOutput?: string
  toolStatus?: string
  usage?: { inputTokens: number; outputTokens: number }
  error?: string
}

// === Message ===
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  timestamp: number
}

export interface ToolCall {
  name: string
  input: Record<string, unknown>
  output?: string
  status: 'running' | 'success' | 'error' | 'rejected'
}

// === Conversation ===
export interface Conversation {
  id: string
  title: string
  workspaceId?: string
  updatedAt: number
  createdAt: number
  messageCount?: number
}

export interface Workspace {
  id: string
  name: string
  path: string
  isSystem?: number
  createdAt: number
  updatedAt: number
}

// === Time grouping (sidebar) ===
export interface ConvGroup {
  label: string
  conversations: Conversation[]
}

// === MCP Server ===
export interface McpServer {
  id?: string
  name: string
  transport: 'stdio' | 'http'
  command?: string
  args?: string
  envVars?: string
  url?: string
  headers?: string
  status: 'connected' | 'disconnected' | 'error'
  scope?: 'user' | 'shared'
  canManage?: boolean
  createdAt?: number
  updatedAt?: number
}

// === MCP Tool ===
export interface McpTool {
  id?: string
  serverId: string
  toolName: string
  description: string
  enabled: boolean
  source: 'discovered' | 'manual'
  createdAt?: number
}

// === Skill (legacy) ===
export interface Skill {
  name: string
  source: 'builtin' | 'community' | 'custom'
  path?: string
  enabled: boolean
}

// === Skill Config ===
export interface SkillConfig {
  id?: string
  skillName: string
  displayName: string
  description: string
  scope?: 'user' | 'shared'
  source: 'builtin' | 'git' | 'url' | 'local' | 'upload' | 'sync' | 'marketplace'
  sourceUrl?: string
  version?: string
  enabled: boolean
  installPath?: string
  createdAt?: number
  updatedAt?: number
  platform?: string
  platformPath?: string
}

// === Model ===
export interface ModelConfig {
  id?: string
  name: string
  baseUrl?: string
  apiKey?: string
  isDefault: boolean
  maxContextTokens?: number
  params?: string
  modelType?: string
  dimensions?: number
  scope?: 'user' | 'shared'
}

// === Memory ===
export interface MemoryItem {
  name: string
  type: 'user' | 'project'
  description: string
  content?: string
  enabled: boolean
  createdAt: number
  updatedAt: number
}

// === Knowledge Base ===
export interface KnowledgeBase {
  id?: string
  name: string
  description?: string
  embedModel?: string
  docCount?: number
  scope?: 'user' | 'shared'
  canManage?: boolean
  createdAt?: number
  updatedAt?: number
}

// === File Attachment ===
export interface FileAttachment {
  path: string       // workspace 相对路径
  name: string       // 原始文件名（展示用）
  size: number       // 字节
  status: 'uploading' | 'done' | 'error'
}

// === Security Rule ===
export interface SecurityRule {
  id?: string
  type: 'SHELL_ALLOWED' | 'DEFAULT_ALLOW' | 'HARD_BLOCK' | 'SOFT_BLOCK'
  value: string
  description: string
  enabled: boolean
  createdAt?: number
  updatedAt?: number
}

// === Confirm Action Event ===
export interface ConfirmActionEvent {
  confirmId: string
  toolName: string
  toolInput: Record<string, unknown>
  rule: string
  ruleDetail: string
}
