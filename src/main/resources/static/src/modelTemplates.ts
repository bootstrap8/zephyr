// src/main/resources/static/src/modelTemplates.ts

export interface ModelTemplate {
  name: string
  matchPattern: RegExp
  paradigm: 'thinking-type' | 'enable-thinking' | 'reasoning-effort' | 'none'
  thinkingOnParams: Record<string, string>
  thinkingOffParams: Record<string, string>
  depthKey: string | null
  depthValues: string[] | null
  depthMin: number | null
  depthMax: number | null
  hideOnThinking: string[]
  canDisable: boolean
  requiresProxy: boolean
}

const HIDE_ON_THINKING = ['temperature', 'top_p', 'max_tokens', 'frequency_penalty', 'presence_penalty']

export const TEMPLATES: ModelTemplate[] = [
  {
    name: 'DeepSeek V4 Pro/Flash',
    matchPattern: /deepseek.*v4/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'reasoning_effort': 'high' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'reasoning_effort',
    depthValues: ['high', 'max'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'DeepSeek R1',
    matchPattern: /deepseek.*r1/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'DeepSeek V3',
    matchPattern: /deepseek.*v3(?:\.\d+)?(?!\d|.*v4)/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: [],
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'OpenAI o3/o4-mini',
    matchPattern: /\bo(?:3|4)-mini\b/i,
    paradigm: 'reasoning-effort',
    thinkingOnParams: { 'reasoning_effort': 'medium' },
    thinkingOffParams: {},
    depthKey: 'reasoning_effort',
    depthValues: ['low', 'medium', 'high'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Claude 3.7/4.x',
    matchPattern: /claude/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'thinking.budget_tokens': '16000' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'thinking.budget_tokens',
    depthValues: null,
    depthMin: 1024, depthMax: 128000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: true,
  },
  {
    name: 'Doubao-Seed 1.6',
    matchPattern: /doubao.*seed/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'GLM-4.5+ (智谱)',
    matchPattern: /glm(?:-\d)?/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Kimi K2 (k2.6/k2.5)',
    matchPattern: /kimi.*k2\.[56]/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Kimi K2 Thinking',
    matchPattern: /kimi.*k2.*think/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Qwen3',
    matchPattern: /qwen3/i,
    paradigm: 'enable-thinking',
    thinkingOnParams: { 'enable_thinking': 'true', 'thinking_budget': '2048' },
    thinkingOffParams: { 'enable_thinking': 'false' },
    depthKey: 'thinking_budget',
    depthValues: null,
    depthMin: 0, depthMax: 16000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'MiniMax-M1',
    matchPattern: /minimax.*m1/i,
    paradigm: 'none',
    thinkingOnParams: { 'thinking_budget': '4096' },
    thinkingOffParams: {},
    depthKey: 'thinking_budget',
    depthValues: null,
    depthMin: 0, depthMax: 40000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'MiMo-V2-Flash (小米)',
    matchPattern: /mimo/i,
    paradigm: 'thinking-type',
    thinkingOnParams: { 'thinking.type': 'enabled', 'thinking.budget_tokens': '2048' },
    thinkingOffParams: { 'thinking.type': 'disabled' },
    depthKey: 'thinking.budget_tokens',
    depthValues: null,
    depthMin: 0, depthMax: 16000,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: true,
    requiresProxy: false,
  },
  {
    name: 'Grok-3-mini',
    matchPattern: /grok.*3.*mini/i,
    paradigm: 'reasoning-effort',
    thinkingOnParams: { 'reasoning_effort': 'low' },
    thinkingOffParams: {},
    depthKey: 'reasoning_effort',
    depthValues: ['low', 'high'],
    depthMin: null, depthMax: null,
    hideOnThinking: HIDE_ON_THINKING,
    canDisable: false,
    requiresProxy: false,
  },
  {
    name: 'Gemma 3',
    matchPattern: /gemma.*3/i,
    paradigm: 'none',
    thinkingOnParams: {},
    thinkingOffParams: {},
    depthKey: null,
    depthValues: null,
    depthMin: null, depthMax: null,
    hideOnThinking: [],
    canDisable: false,
    requiresProxy: false,
  },
]

export function matchTemplate(modelName: string): ModelTemplate | null {
  for (const t of TEMPLATES) {
    if (t.matchPattern.test(modelName)) return t
  }
  return null
}

export function getTemplate(name: string): ModelTemplate | undefined {
  return TEMPLATES.find(t => t.name === name)
}
