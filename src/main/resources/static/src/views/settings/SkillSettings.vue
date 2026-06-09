<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import type { SkillConfig } from '@/types/chat'

const store = useSettingsStore()

const showInstallDialog = ref(false)
const installMethod = ref<'git' | 'url' | 'local' | 'upload' | 'sync' | 'marketplace'>('git')
const gitUrl = ref('')
const gitBranch = ref('main')
const downloadUrl = ref('')
const localPath = ref('')
const uploadFile = ref<File | null>(null)
const installing = ref(false)

const showSyncPanel = ref(false)
const currentPlatform = ref('')
const syncSkills = ref<SkillConfig[]>([])
const selectedSyncSkills = ref<Set<string>>(new Set())
const syncing = ref(false)

const uninstallTarget = ref<SkillConfig | null>(null)
const showUninstallConfirm = ref(false)

const detailSkill = ref<SkillConfig | null>(null)
const showDetail = ref(false)

onMounted(async () => { await store.loadSkills() })

const installMethods = [
  { key: 'git', label: 'Git', icon: 'lucide:git-branch' },
  { key: 'url', label: 'URL', icon: 'lucide:link' },
  { key: 'local', label: '本地', icon: 'lucide:folder' },
  { key: 'upload', label: '上传', icon: 'lucide:upload' },
  { key: 'sync', label: '平台同步', icon: 'lucide:refresh-cw' },
  { key: 'marketplace', label: '市场', icon: 'lucide:store' },
] as const

const platformInfo: Record<string, { icon: string; color: string }> = {
  'claude-code': { icon: 'lucide:sparkles', color: '#d97706' },
  codex: { icon: 'lucide:code-2', color: '#10a37f' },
  opencode: { icon: 'lucide:terminal', color: '#6366f1' },
}

const sourceTag: Record<string, string> = {
  builtin: '内置', git: 'Git', url: 'URL', local: '本地',
  upload: '上传', sync: '平台同步', marketplace: '市场',
}

function openInstallDialog() {
  installMethod.value = 'git'
  gitUrl.value = ''
  gitBranch.value = 'main'
  downloadUrl.value = ''
  localPath.value = ''
  uploadFile.value = null
  showInstallDialog.value = true
}

async function doInstall() {
  installing.value = true
  try {
    if (installMethod.value === 'git') {
      if (!gitUrl.value.trim()) return
      await store.installSkill({ source: 'git', url: gitUrl.value.trim(), branch: gitBranch.value.trim() || 'main' })
    } else if (installMethod.value === 'url') {
      if (!downloadUrl.value.trim()) return
      await store.installSkill({ source: 'url', url: downloadUrl.value.trim() })
    } else if (installMethod.value === 'local') {
      if (!localPath.value.trim()) return
      await store.installSkill({ source: 'local', path: localPath.value.trim() })
    } else if (installMethod.value === 'upload') {
      if (!uploadFile.value) return
      await store.uploadSkill(uploadFile.value)
    } else if (installMethod.value === 'sync') {
      showInstallDialog.value = false
      await openSyncPanel()
      return
    }
    showInstallDialog.value = false
  } catch (_) {
  } finally {
    installing.value = false
  }
}

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files?.length) uploadFile.value = target.files[0]
}

async function openSyncPanel() {
  syncSkills.value = await store.syncScanSkills()
  selectedSyncSkills.value = new Set()
  currentPlatform.value = ''
  showSyncPanel.value = true
}

function selectPlatform(platform: string) {
  currentPlatform.value = platform
  selectedSyncSkills.value = new Set()
}

function toggleSelectAll() {
  const platformSkills = syncSkills.value.filter(s => s.platform === currentPlatform.value)
  const allSelected = platformSkills.every(s => selectedSyncSkills.value.has(s.skillName))
  if (allSelected) {
    platformSkills.forEach(s => selectedSyncSkills.value.delete(s.skillName))
  } else {
    platformSkills.forEach(s => selectedSyncSkills.value.add(s.skillName))
  }
}

async function doSyncInstall() {
  if (selectedSyncSkills.value.size === 0) return
  syncing.value = true
  try {
    await store.syncInstallSkills(currentPlatform.value, [...selectedSyncSkills.value])
    showSyncPanel.value = false
    showInstallDialog.value = false
  } finally {
    syncing.value = false
  }
}

async function doToggle(skill: SkillConfig) {
  if (!skill.id) return
  await store.toggleSkill(skill.id, !skill.enabled)
}

function confirmUninstall(skill: SkillConfig) {
  uninstallTarget.value = skill
  showUninstallConfirm.value = true
}

async function doUninstall() {
  if (!uninstallTarget.value?.id) return
  await store.uninstallSkill(uninstallTarget.value.id)
  showUninstallConfirm.value = false
  uninstallTarget.value = null
}

function showSkillDetail(skill: SkillConfig) {
  detailSkill.value = skill
  showDetail.value = true
}

function closeDetail() {
  showDetail.value = false
  detailSkill.value = null
}

function goBack() { window.history.back() }
</script>

<template>
  <div class="skill-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="goBack"><Icon icon="lucide:chevron-left" /></button>
        <h1>Skill 管理</h1>
      </div>
      <button v-if="store.skills.length > 0" class="btn-primary" @click="openInstallDialog">
        <Icon icon="lucide:plus" /> 安装 Skill
      </button>
    </div>
    <p class="subtitle">管理已安装的 Skills，支持 Git、URL、本地、上传、平台同步多种安装方式</p>

    <div v-if="store.skills.length === 0" class="empty-state">
      <Icon icon="lucide:puzzle" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">还没有安装 Skill</h3>
      <p class="empty-desc">从 Git 仓库、URL、本地目录安装，或上传压缩包。也可以从 Claude Code / Codex / OpenCode 同步已有的 Skill。</p>
      <button class="btn-primary" @click="openInstallDialog">
        <Icon icon="lucide:plus" /> 安装第一个 Skill
      </button>
    </div>

    <div v-else class="skill-list">
      <div v-for="s in store.skills" :key="s.id ?? s.skillName" class="skill-card" @click="showSkillDetail(s)">
        <div class="skill-icon" :class="s.source">
          <Icon :icon="s.source === 'builtin' ? 'lucide:star' : s.source === 'git' ? 'lucide:git-branch' : s.source === 'sync' ? 'lucide:refresh-cw' : s.source === 'upload' ? 'lucide:package' : 'lucide:puzzle'" width="18" />
        </div>
        <div class="skill-info">
          <div class="skill-name">{{ s.displayName || s.skillName }}</div>
          <div v-if="s.description" class="skill-desc">{{ s.description }}</div>
          <div class="skill-meta">
            <span class="badge badge-source" :class="'src-' + s.source">{{ sourceTag[s.source] ?? s.source }}</span>
            <span v-if="s.version" class="badge badge-version">{{ 'v' + s.version }}</span>
          </div>
        </div>
        <div class="skill-actions" @click.stop>
          <label class="toggle-switch" :title="s.enabled ? '禁用' : '启用'">
            <input type="checkbox" :checked="s.enabled" @change="doToggle(s)" />
            <span class="toggle-slider"></span>
          </label>
          <button class="btn-icon" @click="confirmUninstall(s)" title="卸载">
            <Icon icon="lucide:trash-2" width="15" />
          </button>
        </div>
      </div>
    </div>

    <!-- 安装弹窗 -->
    <Teleport to="body">
      <div v-if="showInstallDialog" class="modal-overlay" @click.self="showInstallDialog = false">
        <div class="modal">
          <div class="modal-header">
            <h2>安装 Skill</h2>
            <button class="btn-icon" @click="showInstallDialog = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div class="method-tabs">
              <button
                v-for="m in installMethods"
                :key="m.key"
                :class="{ active: installMethod === m.key }"
                @click="installMethod = m.key"
              >
                <Icon :icon="m.icon" width="14" />
                <span>{{ m.label }}</span>
              </button>
            </div>

            <template v-if="installMethod === 'git'">
              <div class="form-group">
                <label class="form-label">Git 仓库地址</label>
                <input class="form-input" v-model="gitUrl" placeholder="https://github.com/user/skill-repo.git" />
              </div>
              <div class="form-group">
                <label class="form-label">分支（可选）</label>
                <input class="form-input" v-model="gitBranch" placeholder="main" />
              </div>
            </template>

            <template v-if="installMethod === 'url'">
              <div class="form-group">
                <label class="form-label">下载链接</label>
                <input class="form-input" v-model="downloadUrl" placeholder="https://example.com/skill.zip 或 .md 文件链接" />
              </div>
            </template>

            <template v-if="installMethod === 'local'">
              <div class="form-group">
                <label class="form-label">本地路径</label>
                <input class="form-input" v-model="localPath" placeholder="/path/to/skill/directory" />
              </div>
            </template>

            <template v-if="installMethod === 'upload'">
              <div class="upload-area" @click="($refs.fileInput as HTMLInputElement)?.click()">
                <div v-if="uploadFile" class="upload-file-selected">
                  <Icon icon="lucide:file-archive" width="32" />
                  <span>{{ uploadFile.name }}</span>
                </div>
                <template v-else>
                  <Icon icon="lucide:upload" width="32" style="color: var(--el-text-color-placeholder)" />
                  <p>拖拽压缩包到此处，或点击选择</p>
                  <span class="upload-hint">支持 .zip、.tar.gz、.tgz 格式，最大 10MB</span>
                </template>
              </div>
              <input ref="fileInput" type="file" accept=".zip,.tar.gz,.tgz" @change="onFileChange" style="display:none" />
              <div class="upload-note">压缩包内需包含 SKILL.md 文件作为 skill 入口</div>
            </template>

            <template v-if="installMethod === 'sync'">
              <div class="form-group">
                <label class="form-label">点击安装按钮后扫描本地平台</label>
                <p class="form-hint">将扫描 Claude Code / Codex / OpenCode 中已安装的 Skill</p>
              </div>
            </template>

            <template v-if="installMethod === 'marketplace'">
              <div class="form-group">
                <label class="form-label">市场搜索（即将上线）</label>
                <p class="form-hint">敬请期待</p>
              </div>
            </template>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showInstallDialog = false">取消</button>
            <button
              v-if="installMethod !== 'marketplace'"
              class="btn-primary"
              @click="doInstall"
              :disabled="installing"
            >
              {{ installing ? '安装中...' : installMethod === 'sync' ? '扫描平台' : '安装' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 平台同步面板 -->
    <Teleport to="body">
      <div v-if="showSyncPanel" class="modal-overlay" @click.self="showSyncPanel = false">
        <div class="modal" style="width: 720px; max-width: calc(100vw - 48px)">
          <div class="modal-header">
            <h2>平台同步</h2>
            <button class="btn-icon" @click="showSyncPanel = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div v-if="!currentPlatform">
              <p style="font-size:13px;color:var(--el-text-color-secondary);margin:0 0 16px">选择要同步的平台</p>
              <div
                v-for="platform in ['claude-code', 'codex', 'opencode']"
                :key="platform"
                class="platform-row"
                @click="selectPlatform(platform)"
              >
                <div class="platform-icon" :style="{ background: platformInfo[platform]?.color }">
                  <Icon :icon="platformInfo[platform]?.icon ?? 'lucide:folder'" width="18" :style="{ color: '#fff' }" />
                </div>
                <div class="platform-info">
                  <span class="platform-name">{{ platform === 'claude-code' ? 'Claude Code' : platform === 'codex' ? 'Codex' : 'OpenCode' }}</span>
                  <span class="platform-path">~{{ platform === 'claude-code' ? '/.claude' : platform === 'codex' ? '/.codex' : '/.opencode' }}/skills/</span>
                </div>
                <span class="platform-count">{{ syncSkills.filter(s => s.platform === platform).length }} 个</span>
                <Icon icon="lucide:chevron-right" width="16" style="color:var(--el-text-color-placeholder)" />
              </div>
            </div>
            <div v-else>
              <button class="back-link" @click="currentPlatform = ''">
                <Icon icon="lucide:chevron-left" width="14" /> 返回
              </button>
              <div v-for="s in syncSkills.filter(s => s.platform === currentPlatform)" :key="s.skillName" class="sync-skill-row" @click="selectedSyncSkills.has(s.skillName) ? selectedSyncSkills.delete(s.skillName) : selectedSyncSkills.add(s.skillName)">
                <input type="checkbox" :checked="selectedSyncSkills.has(s.skillName)" class="sync-checkbox" @click.stop />
                <div class="sync-skill-info">
                  <span class="sync-skill-name" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{{ s.displayName || s.skillName }}</span>
                  <span v-if="s.description" class="sync-skill-desc" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:360px">{{ s.description }}</span>
                  <span v-if="s.version" class="badge badge-version">v{{ s.version }}</span>
                </div>
              </div>
              <div class="sync-footer">
                <button class="btn-link" @click="toggleSelectAll">全选 / 取消全选</button>
                <span class="select-count">已选 {{ selectedSyncSkills.size }} 个</span>
              </div>
            </div>
          </div>
          <div class="modal-footer" v-if="currentPlatform">
            <button class="btn-secondary" @click="currentPlatform = ''">返回</button>
            <button class="btn-primary" @click="doSyncInstall" :disabled="syncing || selectedSyncSkills.size === 0">
              {{ syncing ? '同步中...' : `同步 ${selectedSyncSkills.size} 个 Skill` }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 卸载确认弹窗 -->
    <Teleport to="body">
      <div v-if="showUninstallConfirm && uninstallTarget" class="modal-overlay" @click.self="showUninstallConfirm = false">
        <div class="modal" style="width: 440px">
          <div class="modal-header">
            <h2>卸载 Skill</h2>
            <button class="btn-icon" @click="showUninstallConfirm = false"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div class="warn-title">
              <Icon icon="lucide:alert-triangle" width="18" style="color:var(--el-color-danger)" />
              <span>此操作将同时删除以下内容：</span>
            </div>
            <div class="warn-box">
              <div class="warn-item">
                <span class="warn-num">1</span>
                <span>数据库记录</span>
                <span class="warn-detail">skill_configs 表</span>
              </div>
              <div class="warn-item">
                <span class="warn-num">2</span>
                <span>磁盘文件</span>
                <span class="warn-detail" style="font-family:monospace">~/.zephyr/skills/{{ uninstallTarget.skillName }}/</span>
              </div>
            </div>
            <p class="uninstall-msg">确定要卸载 <strong>{{ uninstallTarget.displayName || uninstallTarget.skillName }}</strong> 吗？此操作不可撤销。</p>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="showUninstallConfirm = false">取消</button>
            <button class="btn-danger" @click="doUninstall">卸载</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Skill 详情弹窗 -->
    <Teleport to="body">
      <div v-if="showDetail && detailSkill" class="modal-overlay" @click.self="closeDetail">
        <div class="modal" style="width: 680px; max-width: calc(100vw - 48px)">
          <div class="modal-header">
            <h2>{{ detailSkill.displayName || detailSkill.skillName }}</h2>
            <button class="btn-icon" @click="closeDetail"><Icon icon="lucide:x" width="18" /></button>
          </div>
          <div class="modal-body">
            <div class="detail-grid">
              <div class="detail-item">
                <span class="detail-label">名称</span>
                <span class="detail-value">{{ detailSkill.skillName }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">来源</span>
                <span class="detail-value"><span class="badge badge-source" :class="'src-' + detailSkill.source">{{ sourceTag[detailSkill.source] ?? detailSkill.source }}</span></span>
              </div>
              <div class="detail-item" v-if="detailSkill.version">
                <span class="detail-label">版本</span>
                <span class="detail-value font-mono">v{{ detailSkill.version }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">状态</span>
                <span class="detail-value" :style="{ color: detailSkill.enabled ? 'var(--el-color-success)' : 'var(--el-text-color-placeholder)' }">{{ detailSkill.enabled ? '已启用' : '已禁用' }}</span>
              </div>
              <div class="detail-item" v-if="detailSkill.sourceUrl">
                <span class="detail-label">来源地址</span>
                <span class="detail-value font-mono" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{{ detailSkill.sourceUrl }}</span>
              </div>
              <div class="detail-item" v-if="detailSkill.installPath">
                <span class="detail-label">安装路径</span>
                <span class="detail-value font-mono" style="white-space:nowrap">{{ detailSkill.installPath }}</span>
              </div>
              <div class="detail-item" v-if="detailSkill.createdAt">
                <span class="detail-label">安装时间</span>
                <span class="detail-value">{{ new Date(detailSkill.createdAt * 1000).toLocaleString() }}</span>
              </div>
            </div>
            <div v-if="detailSkill.description" class="detail-desc-block">
              <span class="detail-label">描述</span>
              <p class="detail-desc-text">{{ detailSkill.description }}</p>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-secondary" @click="closeDetail">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.skill-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; }
.back-btn:hover { background: var(--el-fill-color-light); }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-secondary:hover { background: var(--el-fill-color-light); }
.btn-danger { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-danger); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; }
.btn-danger:hover { background: var(--el-color-danger-light-3); }
.btn-link { font-size: 12px; border: none; background: transparent; color: var(--el-color-primary); cursor: pointer; font-family: inherit; }
.btn-icon { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); flex-shrink: 0; transition: background 150ms; }
.btn-icon:hover { background: var(--el-fill-color-light); }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 420px; margin: 0 auto 24px; }

.skill-list { display: flex; flex-direction: column; gap: 10px; }
.skill-card { background: var(--el-fill-color-lighter); border-radius: 12px; padding: 16px 20px; display: flex; align-items: center; gap: 14px; transition: box-shadow 200ms; cursor: pointer; }
.skill-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.skill-icon { width: 36px; height: 36px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.skill-icon.builtin { background: var(--el-text-color-primary); }
.skill-icon.git { background: #e88d4a; }
.skill-icon.url { background: var(--el-color-primary); }
.skill-icon.local, .skill-icon.upload { background: var(--el-color-success); }
.skill-icon.sync { background: #6366f1; }
.skill-icon.marketplace { background: #d97706; }

.skill-info { flex: 1; min-width: 0; }
.skill-name { font-size: 15px; font-weight: 500; color: var(--el-text-color-primary); }
.skill-desc { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-meta { display: flex; gap: 8px; margin-top: 6px; }

.badge { font-size: 10px; padding: 2px 8px; border-radius: 99px; }
.badge-source { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.badge-source.src-builtin { background: var(--el-fill-color); }
.badge-source.src-git { background: rgba(232,141,74,0.12); color: #e88d4a; }
.badge-source.src-sync { background: rgba(99,102,241,0.12); color: #6366f1; }
.badge-source.src-upload { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.badge-version { background: var(--el-fill-color); color: var(--el-text-color-placeholder); font-family: monospace; }

.skill-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

.toggle-switch { position: relative; width: 38px; height: 22px; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--el-border-color); border-radius: 99px; cursor: pointer; transition: background 200ms; }
.toggle-slider::after { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: transform 200ms; }
.toggle-switch input:checked + .toggle-slider { background: var(--el-color-primary); }
.toggle-switch input:checked + .toggle-slider::after { transform: translateX(16px); }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.35); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.modal { background: var(--el-bg-color); border-radius: 16px; width: 520px; max-width: calc(100vw - 48px); max-height: 92vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,0.1); scrollbar-gutter: stable; }
.modal::-webkit-scrollbar { width: 6px; }
.modal::-webkit-scrollbar-track { background: transparent; border-radius: 16px; }
.modal::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 3px; }
.modal::-webkit-scrollbar-thumb:hover { background: var(--el-text-color-placeholder); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 24px 28px 0; }
.modal-header h2 { font-family: Georgia, serif; font-size: 24px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin: 0; }
.modal-body { padding: 24px 28px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 0 28px 24px; }

.method-tabs { display: flex; gap: 4px; margin-bottom: 24px; padding: 3px; background: var(--el-fill-color-lighter); border-radius: 8px; }
.method-tabs button { display: flex; align-items: center; gap: 4px; flex: 1; padding: 7px 6px; border-radius: 6px; border: none; background: transparent; font-size: 12px; color: var(--el-text-color-secondary); cursor: pointer; font-family: inherit; justify-content: center; transition: all 200ms; white-space: nowrap; }
.method-tabs button.active { background: var(--el-bg-color); color: var(--el-text-color-primary); box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.method-tabs button:hover:not(.active) { color: var(--el-text-color-primary); }

.form-group { margin-bottom: 16px; }
.form-label { display: block; font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); margin-bottom: 6px; }
.form-input { width: 100%; height: 40px; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; box-sizing: border-box; }
.form-input:focus { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(204,120,92,0.12); }
.form-hint { font-size: 12px; color: var(--el-text-color-placeholder); margin: 0; }

.upload-area { border: 2px dashed var(--el-border-color); border-radius: 12px; padding: 32px; text-align: center; cursor: pointer; transition: border-color 200ms; }
.upload-area:hover { border-color: var(--el-color-primary); }
.upload-area p { font-size: 14px; color: var(--el-text-color-primary); margin: 8px 0 4px; }
.upload-hint { font-size: 12px; color: var(--el-text-color-placeholder); }
.upload-file-selected { display: flex; align-items: center; gap: 12px; justify-content: center; }
.upload-file-selected span { font-size: 14px; color: var(--el-text-color-primary); font-family: monospace; }
.upload-note { margin-top: 12px; font-size: 12px; background: #fef9e7; border-radius: 8px; padding: 10px 14px; color: #b0902c; }

.platform-row { display: flex; align-items: center; gap: 10px; padding: 12px; border-radius: 8px; background: var(--el-fill-color-lighter); margin-bottom: 8px; cursor: pointer; transition: background 150ms; }
.platform-row:hover { background: var(--el-fill-color-light); }
.platform-icon { width: 34px; height: 34px; border-radius: 8px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.platform-info { flex: 1; }
.platform-name { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); display: block; }
.platform-path { font-size: 11px; color: var(--el-text-color-placeholder); font-family: monospace; }
.platform-count { font-size: 12px; padding: 2px 8px; border-radius: 99px; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }

.back-link { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; border: none; background: transparent; color: var(--el-color-primary); cursor: pointer; font-family: inherit; margin-bottom: 12px; padding: 0; }
.sync-skill-row { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; background: var(--el-fill-color-lighter); margin-bottom: 4px; cursor: pointer; transition: background 150ms; }
.sync-skill-row:hover { background: var(--el-fill-color-light); }
.sync-checkbox { accent-color: var(--el-color-primary); flex-shrink: 0; }
.sync-skill-info { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; }
.sync-skill-name { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); }
.sync-skill-desc { font-size: 11px; color: var(--el-text-color-placeholder); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sync-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.select-count { font-size: 12px; color: var(--el-text-color-secondary); }

.warn-title { display: flex; align-items: center; gap: 6px; font-size: 14px; color: var(--el-color-danger); font-weight: 500; margin-bottom: 12px; }
.warn-box { background: #fef2f2; border-radius: 10px; padding: 14px 16px; margin-bottom: 16px; }
.warn-item { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; color: #991b1b; }
.warn-item:last-child { margin-bottom: 0; }
.warn-num { width: 18px; height: 18px; border-radius: 50%; background: #fca5a5; color: #991b1b; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.warn-detail { font-size: 11px; color: #b91c1c; margin-left: auto; }
.uninstall-msg { font-size: 13px; color: var(--el-text-color-secondary); margin: 0; }

@media (max-width: 640px) {
  .skill-page { padding: 24px 16px 64px; }
  h1 { font-size: 28px; }
}

/* Detail dialog */
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
.detail-item { display: flex; flex-direction: column; gap: 2px; }
.detail-label { font-size: 11px; color: var(--el-text-color-placeholder); text-transform: uppercase; letter-spacing: 0.5px; }
.detail-value { font-size: 14px; color: var(--el-text-color-primary); }
.detail-value.font-mono { font-family: monospace; font-size: 12px; }
.detail-desc-block { border-top: 1px solid var(--el-border-color-lighter); padding-top: 12px; }
.detail-desc-text { font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; margin: 6px 0 0; white-space: pre-wrap; }
</style>

<style>
html.dark .upload-note { background: rgba(251,191,36,0.1); color: #fbbf24; }
html.dark .warn-box { background: rgba(198,69,69,0.1); }
html.dark .warn-item { color: #fca5a5; }
html.dark .warn-num { background: rgba(198,69,69,0.3); color: #fca5a5; }
html.dark .warn-detail { color: #f87171; }
</style>
