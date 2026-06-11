// === SSE Chat Event ===
export interface ChatEvent {
  type: 'token' | 'thinking' | 'tool_call' | 'tool_result' | 'usage' | 'compaction' | 'done' | 'error'
  content?: string
  toolName?: string
  toolInput?: Record<string, unknown>
  toolOutput?: string
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
  status: 'running' | 'success' | 'error'
}

// === Conversation ===
export interface Conversation {
  id: string
  title: string
  updatedAt: number
  createdAt: number
  messageCount?: number
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
