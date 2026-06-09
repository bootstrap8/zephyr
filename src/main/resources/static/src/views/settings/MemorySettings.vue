<script lang="ts" setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import MarkdownIt from 'markdown-it'

const router = useRouter()
const store = useSettingsStore()

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
function renderMarkdown(text: string) {
  if (!text) return ''
  return md.render(text)
}

const currentFilter = ref('all')
const expandedName = ref<string | null>(null)
const detailCache = ref<Record<string, string>>({})

const dialogVisible = ref(false)
const dialogTitle = ref('新增记忆')
const editingOldName = ref('')
const form = reactive({ name: '', type: 'user', content: '' })

const selectedNames = ref<string[]>([])
const deleteDialogVisible = ref(false)
const deleteTargets = ref<string[]>([])

const filteredMemories = computed(() => {
  if (currentFilter.value === 'all') return store.memories
  return store.memories.filter((m: any) => m.type === currentFilter.value)
})

const typeLabel: Record<string, string> = { user: '用户', project: '项目' }

function fmtTime(ts: number) {
  return new Date(ts * 1000).toISOString().slice(0, 10)
}

function toggleExpand(name: string) {
  if (expandedName.value === name) {
    expandedName.value = null
    return
  }
  expandedName.value = name
  if (!detailCache.value[name]) {
    store.loadMemoryDetail(name).then((m: any) => {
      if (m) detailCache.value[name] = m.content || ''
    })
  }
}

function openCreate() {
  dialogTitle.value = '新增记忆'
  editingOldName.value = ''
  form.name = ''
  form.type = 'user'
  form.content = ''
  dialogVisible.value = true
}

function openEdit(m: any) {
  dialogTitle.value = '编辑记忆'
  editingOldName.value = m.name
  form.name = m.name
  form.type = m.type
  if (!detailCache.value[m.name]) {
    store.loadMemoryDetail(m.name).then((d: any) => {
      if (d) {
        detailCache.value[m.name] = d.content || ''
        form.content = d.content || ''
      }
    })
  } else {
    form.content = detailCache.value[m.name] || ''
  }
  dialogVisible.value = true
}

async function saveMemory() {
  if (!form.name.trim()) { msg('请输入名称', 'warning'); return }
  if (!form.content.trim()) { msg('请输入内容', 'warning'); return }

  let ok: boolean
  if (editingOldName.value) {
    ok = await store.updateMemory(editingOldName.value, form.name.trim(), form.type, form.content.trim())
  } else {
    ok = await store.createMemory(form.name.trim(), form.type, form.content.trim())
  }

  if (ok) {
    dialogVisible.value = false
    delete detailCache.value[editingOldName.value || form.name]
    await store.loadMemories(currentFilter.value === 'all' ? undefined : currentFilter.value)
  }
}

function confirmDeleteSingle(m: any) {
  deleteTargets.value = [m.name]
  deleteDialogVisible.value = true
}

function confirmDeleteBatch() {
  if (selectedNames.value.length === 0) return
  deleteTargets.value = [...selectedNames.value]
  deleteDialogVisible.value = true
}

async function executeDelete() {
  const ok = await store.deleteMemories(deleteTargets.value)
  if (ok) {
    deleteDialogVisible.value = false
    selectedNames.value = []
    deleteTargets.value.forEach(n => delete detailCache.value[n])
    if (expandedName.value && deleteTargets.value.includes(expandedName.value)) {
      expandedName.value = null
    }
    await store.loadMemories(currentFilter.value === 'all' ? undefined : currentFilter.value)
  }
}

function toggleSelect(name: string) {
  const idx = selectedNames.value.indexOf(name)
  if (idx >= 0) {
    selectedNames.value.splice(idx, 1)
  } else {
    selectedNames.value.push(name)
  }
}

async function setFilter(type: string) {
  currentFilter.value = type
  expandedName.value = null
  selectedNames.value = []
  await store.loadMemories(type === 'all' ? undefined : type)
}

onMounted(() => { store.loadMemories() })
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/chat')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>记忆管理</h2>
    </div>

    <div class="page-toolbar">
      <div class="filter-tabs">
        <button :class="['filter-tab', { active: currentFilter === 'all' }]" @click="setFilter('all')">
          全部
        </button>
        <button :class="['filter-tab', { active: currentFilter === 'user' }]" @click="setFilter('user')">
          用户
        </button>
        <button :class="['filter-tab', { active: currentFilter === 'project' }]" @click="setFilter('project')">
          项目
        </button>
      </div>
      <el-button type="primary" @click="openCreate">
        <Icon icon="lucide:plus" style="margin-right:4px" /> 新增记忆
      </el-button>
    </div>

    <div v-if="selectedNames.length > 0" class="batch-bar">
      <span>已选 <strong>{{ selectedNames.length }}</strong> 项</span>
      <el-button type="danger" size="small" @click="confirmDeleteBatch">批量删除</el-button>
    </div>

    <div v-if="filteredMemories.length === 0" class="empty-state" style="text-align:center;padding:80px 20px;color:var(--el-text-color-secondary)">
      <Icon icon="lucide:file-text" :width="40" />
      <h3 style="font-family:Georgia,serif;font-size:18px;font-weight:400;color:var(--el-text-color-primary);margin:12px 0 4px">暂无记忆</h3>
      <p style="font-size:14px">点击「新增记忆」创建第一条</p>
    </div>

    <div v-else class="card-list">
      <div v-for="m in filteredMemories" :key="m.name" class="memory-card">
        <div class="card-inner">
          <div class="card-header">
            <el-checkbox
              :model-value="selectedNames.includes(m.name)"
              @change="toggleSelect(m.name)"
            />
            <Icon icon="lucide:file-text" class="card-icon" />
            <div class="card-body">
              <div class="card-title" @click="toggleExpand(m.name)">{{ m.name }}</div>
              <div class="card-desc">{{ m.description }}</div>
              <div class="card-meta">
                <span class="type-badge" :class="m.type">{{ typeLabel[m.type] }}</span>
                <span>{{ fmtTime(m.updatedAt) }}</span>
              </div>
            </div>
            <div class="card-actions">
              <el-tooltip content="编辑">
                <el-button circle size="small" @click="openEdit(m)">
                  <Icon icon="lucide:edit-3" />
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除">
                <el-button circle size="small" @click="confirmDeleteSingle(m)">
                  <Icon icon="lucide:trash-2" />
                </el-button>
              </el-tooltip>
            </div>
          </div>
        </div>
        <div v-if="expandedName === m.name" class="card-expand">
          <div class="card-expand-body markdown-body" v-html="renderMarkdown(detailCache[m.name] || '')"></div>
          <div class="card-expand-footer">
            <span>创建于 {{ fmtTime(m.createdAt) }}</span>
            <span>·</span>
            <span>更新于 {{ fmtTime(m.updatedAt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="简短标题" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width:100%">
            <el-option label="用户" value="user" />
            <el-option label="项目" value="project" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="记忆详情，支持 Markdown" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMemory">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deleteDialogVisible" title="确认删除" width="400px">
      <p style="margin-bottom:10px">确定删除以下 {{ deleteTargets.length }} 条记忆？</p>
      <ul style="padding-left:18px;color:var(--el-text-color-regular)">
        <li v-for="n in deleteTargets" :key="n">{{ n }}</li>
      </ul>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="executeDelete">删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-page { max-width: 720px; margin: 0 auto; padding: 24px; }

.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn {
  width: 36px; height: 36px; border-radius: 50%;
  border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary); font-size: 18px;
}
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.page-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 20px; }

.filter-tabs { display: flex; gap: 4px; margin-right: auto; }
.filter-tab {
  padding: 6px 14px; border-radius: 8px; border: none;
  background: transparent; color: var(--el-text-color-secondary);
  font-family: inherit; font-size: 13px; font-weight: 500; cursor: pointer;
  transition: all 0.15s;
}
.filter-tab:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.filter-tab.active { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }

.batch-bar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 12px;
  padding: 8px 12px; border-radius: 8px;
  background: var(--el-fill-color-light); font-size: 13px; color: var(--el-text-color-secondary);
}

.card-list { display: flex; flex-direction: column; gap: 1px; background: var(--el-border-color); border-radius: 12px; overflow: hidden; }

.memory-card { background: var(--el-bg-color); }
.card-inner { padding: 14px 16px; }
.card-header { display: flex; align-items: flex-start; gap: 10px; }
.card-icon { color: var(--el-text-color-secondary); font-size: 16px; flex-shrink: 0; margin-top: 1px; }
.card-body { flex: 1; min-width: 0; }
.card-title { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); cursor: pointer; }
.card-title:hover { color: var(--el-color-primary); }
.card-desc { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: var(--el-text-color-placeholder); }
.type-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; }
.type-badge.user { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.type-badge.project { background: var(--el-color-success-light-9); color: var(--el-color-success); }

.card-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }

.card-expand-body {
  padding: 0 16px 14px;
  margin: 0 16px 0 41px;
  border-top: 1px solid var(--el-border-color);
  font-size: 14px; color: var(--el-text-color-regular); line-height: 1.65;
}
.card-expand-body :deep(p) { margin: 6px 0; }
.card-expand-body :deep(ul),
.card-expand-body :deep(ol) { padding-left: 24px; margin: 6px 0; }
.card-expand-body :deep(li) { margin: 3px 0; }
.card-expand-body :deep(a) { color: var(--el-color-primary); }
.card-expand-body :deep(blockquote) { border-left: 3px solid var(--el-color-primary-light-5); padding: 4px 12px; margin: 8px 0; color: var(--el-text-color-secondary); }
.card-expand-body :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; }
.card-expand-body :deep(th),
.card-expand-body :deep(td) { border: 1px solid var(--el-border-color); padding: 6px 10px; text-align: left; font-size: 0.95em; }
.card-expand-body :deep(th) { background: var(--el-fill-color-light); font-weight: 600; }
.card-expand-body :deep(code) { background: var(--el-fill-color); padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', monospace; font-size: 13px; color: var(--el-color-primary-dark-2); }
.card-expand-body :deep(pre) { background: #181715; color: #faf9f5; border-radius: 8px; padding: 14px; margin: 8px 0; overflow-x: auto; font-family: 'JetBrains Mono', monospace; font-size: 13px; line-height: 1.55; }
.card-expand-body :deep(pre code) { background: transparent; color: inherit; padding: 0; border-radius: 0; font-size: inherit; }
.card-expand-body :deep(strong) { font-weight: 600; color: var(--el-text-color-primary); }
.card-expand-body :deep(h1),
.card-expand-body :deep(h2),
.card-expand-body :deep(h3),
.card-expand-body :deep(h4),
.card-expand-body :deep(h5),
.card-expand-body :deep(h6) { font-family: Georgia, serif; font-weight: 400; margin: 12px 0 6px; color: var(--el-text-color-primary); }
.card-expand-body :deep(h1) { font-size: 1.4em; }
.card-expand-body :deep(h2) { font-size: 1.25em; }
.card-expand-body :deep(h3) { font-size: 1.15em; }
.card-expand-body :deep(h4),
.card-expand-body :deep(h5),
.card-expand-body :deep(h6) { font-size: 1.05em; }
.card-expand-footer {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px; font-size: 12px; color: var(--el-text-color-placeholder);
  padding: 0 16px 12px 41px;
}

/* dark mode */
html.dark .memory-card { background: var(--el-bg-color); }
html.dark .filter-tab:hover { background: var(--el-fill-color); }
html.dark .filter-tab.active { background: var(--el-fill-color); }
html.dark .back-btn:hover { background: var(--el-fill-color); }
</style>
