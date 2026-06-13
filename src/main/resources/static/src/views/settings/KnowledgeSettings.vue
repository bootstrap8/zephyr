<script lang="ts" setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getLangData } from '@/i18n/locale'
import { useSettingsStore } from '@/store/settings'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const router = useRouter()
const langData = getLangData()
const store = useSettingsStore()
const dialogVisible = ref(false)
const dialogTitle = ref('')
const editingId = ref('')
const form = reactive({ name: '', description: '', embedModelId: '' })
const embedModels = ref<any[]>([])

const fetchEmbedModels = () => {
  axios({ url: '/model-config/list', method: 'get', params: { modelType: 'embedding' } })
    .then(res => { if (res.data.state === 'OK') embedModels.value = res.data.body })
}

const openCreate = () => {
  dialogTitle.value = langData.knowledgeMgmt_createKb
  editingId.value = ''
  form.name = ''; form.description = ''; form.embedModelId = ''
  fetchEmbedModels()
  dialogVisible.value = true
}

const openEdit = (kb: any) => {
  dialogTitle.value = langData.knowledgeMgmt_editKb
  editingId.value = kb.id
  form.name = kb.name; form.description = kb.description || ''; form.embedModelId = kb.embedModelId || ''
  fetchEmbedModels()
  dialogVisible.value = true
}

const saveKb = async () => {
  if (!form.name.trim()) { msg(langData.memoryMgmt_nameRequired, 'warning'); return }
  const url = editingId.value ? '/knowledge/kb/update' : '/knowledge/kb/create'
  const data: any = { name: form.name.trim(), description: form.description.trim(), embedModelId: form.embedModelId }
  if (editingId.value) data.id = editingId.value
  try {
    const res = await axios({ url, method: 'post', data })
    if (res.data.state === 'OK') { dialogVisible.value = false; store.loadKnowledgeBases() }
    else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '保存失败', 'error') }
}

const deleteKb = (kb: any) => {
  ElMessageBox.confirm(
    langData.confirmDelete + ' ' + kb.name,
    langData.knowledgeMgmt_deleteKb,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => {
    axios({ url: '/knowledge/kb/delete', method: 'post', data: { id: kb.id } })
      .then(res => { if (res.data.state === 'OK') store.loadKnowledgeBases() })
      .catch(err => msg(err?.response?.data?.errorMessage || '删除失败', 'error'))
  }).catch(() => {})
}

function fmtTime(ts: number) {
  return ts ? new Date(ts * 1000).toISOString().slice(0, 10) : '-'
}

onMounted(() => { store.loadKnowledgeBases() })
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/chat')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>{{ langData.knowledgeMgmt_title }}</h2>
    </div>

    <div v-if="store.knowledgeBases.length > 0" class="page-toolbar">
      <div style="flex:1"></div>
      <el-button type="primary" @click="openCreate">
        <Icon icon="lucide:plus" style="margin-right:4px" /> {{ langData.knowledgeMgmt_createKb }}
      </el-button>
    </div>

    <div v-if="store.knowledgeBases.length === 0" class="empty-state">
      <Icon icon="lucide:library" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.knowledgeMgmt_noKb }}</h3>
      <p class="empty-desc">{{ langData.knowledgeMgmt_noKbHint }}</p>
      <button class="btn-primary" @click="openCreate">
        <Icon icon="lucide:plus" /> {{ langData.knowledgeMgmt_createKb }}
      </button>
    </div>

    <div v-else class="card-list">
      <div v-for="kb in store.knowledgeBases" :key="kb.id" class="kb-card" @click="router.push('/settings/knowledge/' + kb.id + '/docs')">
        <div class="card-inner">
          <div class="card-header">
            <Icon icon="lucide:library" class="card-icon" />
            <div class="card-body">
              <div class="card-title">{{ kb.name }}</div>
              <div class="card-desc" v-if="kb.description">{{ kb.description }}</div>
              <div class="card-meta">
                <span class="kb-embed-badge">{{ kb.embedModelName || langData.knowledgeMgmt_embedModel }}</span>
                <span>{{ langData.knowledgeMgmt_docCount.replace('{count}', kb.docCount || 0) }}</span>
                <span>{{ fmtTime(kb.updatedAt) }}</span>
              </div>
            </div>
            <div class="card-actions" @click.stop>
              <el-tooltip :content="langData.btnEdit">
                <el-button circle size="small" @click="openEdit(kb)">
                  <Icon icon="lucide:edit-3" />
                </el-button>
              </el-tooltip>
              <el-tooltip :content="langData.btnDelete">
                <el-button circle size="small" @click="deleteKb(kb)">
                  <Icon icon="lucide:trash-2" />
                </el-button>
              </el-tooltip>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form :model="form" label-position="top">
        <el-form-item :label="langData.knowledgeMgmt_nameLabel" required>
          <el-input v-model="form.name" :placeholder="langData.formInputPlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.knowledgeMgmt_descLabel">
          <el-input v-model="form.description" type="textarea" :rows="3" :placeholder="langData.formInputPlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.knowledgeMgmt_embedModel">
          <el-select v-model="form.embedModelId" style="width:100%" :placeholder="langData.formSelectPlaceholder">
            <el-option v-for="m in embedModels" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ langData.btnCancel }}</el-button>
        <el-button type="primary" @click="saveKb">{{ langData.btnSave }}</el-button>
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

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 420px; margin: 0 auto 24px; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }

.card-list { display: flex; flex-direction: column; gap: 1px; background: var(--el-border-color); border-radius: 12px; overflow: hidden; }

.kb-card { background: var(--el-bg-color); cursor: pointer; transition: background 0.15s; }
.kb-card:hover { background: var(--el-fill-color-light); }
.card-inner { padding: 14px 16px; }
.card-header { display: flex; align-items: flex-start; gap: 10px; }
.card-icon { color: var(--el-color-primary); font-size: 20px; flex-shrink: 0; margin-top: 2px; }
.card-body { flex: 1; min-width: 0; }
.card-title { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); }
.card-desc { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: var(--el-text-color-placeholder); }
.kb-embed-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }

.card-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }

/* dark mode */
html.dark .kb-card { background: var(--el-bg-color); }
html.dark .kb-card:hover { background: var(--el-fill-color); }
html.dark .back-btn:hover { background: var(--el-fill-color); }
</style>
