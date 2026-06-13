<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import MarkdownEditorDialog from './MarkdownEditorDialog.vue'

const router = useRouter()
const route = useRoute()
const langData = getLangData()

const kbId = route.params.kbId as string
const kbName = ref('')
const docs = ref<any[]>([])
const uploadVisible = ref(false)
const uploadFile = ref<File | null>(null)
const uploading = ref(false)

const editorVisible = ref(false)
const editDoc = ref<any>(null)

const headerCellStyle = () => {
  return { backgroundColor: 'var(--el-fill-color-light)', color: 'var(--el-text-color-primary)', fontWeight: 600, whiteSpace: 'nowrap' }
}

const openCreateInline = () => {
  editDoc.value = null
  editorVisible.value = true
}

const openEditInline = (doc: any) => {
  if (doc.sourceType !== 'inline') { msg(langData.knowledgeMgmt_onlyInlineEdit, 'warning'); return }
  editDoc.value = doc
  editorVisible.value = true
}

const onDocSaved = () => { fetchDocs() }

const fetchDocs = () => {
  axios({ url: '/knowledge/doc/list', method: 'get', params: { kbId } })
    .then(res => { if (res.data.state === 'OK') docs.value = res.data.body })
    .catch(err => msg(err?.response?.data?.errorMessage || '加载失败', 'error'))
}

const fetchKbName = () => {
  axios({ url: '/knowledge/kb/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const kb = res.data.body.find((k: any) => k.id === kbId)
        if (kb) kbName.value = kb.name
      }
    })
}

const handleUpload = () => {
  if (!uploadFile.value) { msg('请选择文件', 'warning'); return }
  uploading.value = true
  const formData = new FormData()
  formData.append('file', uploadFile.value)
  formData.append('kbId', kbId)
  axios({ url: '/knowledge/doc/upload', method: 'post', data: formData, headers: { 'Content-Type': 'multipart/form-data' } })
    .then(res => {
      uploading.value = false
      if (res.data.state === 'OK') { uploadVisible.value = false; uploadFile.value = null; fetchDocs() }
      else msg(res.data.errorMessage, 'warning')
    })
    .catch(err => {
      uploading.value = false
      msg(err?.response?.data?.errorMessage || '上传失败', 'error')
    })
}

const deleteDoc = (doc: any) => {
  ElMessageBox.confirm(
    langData.knowledgeMgmt_deleteDocConfirm + ' ' + doc.fileName,
    langData.knowledgeMgmt_deleteKb,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => {
    axios({ url: '/knowledge/doc/delete', method: 'post', data: { id: doc.id } })
      .then(res => { if (res.data.state === 'OK') fetchDocs() })
      .catch(err => msg(err?.response?.data?.errorMessage || '删除失败', 'error'))
  }).catch(() => {})
}

const reParse = (doc: any) => {
  axios({ url: '/knowledge/doc/re-parse', method: 'post', data: { id: doc.id, kbId } })
    .then(res => { if (res.data.state === 'OK') fetchDocs() })
    .catch(err => msg(err?.response?.data?.errorMessage || '重新解析失败', 'error'))
}

const statusTagType = (status: string) => status === 'ready' ? 'success' : status === 'error' ? 'danger' : 'warning'
const statusLabel = (status: string) => {
  if (status === 'ready') return langData.knowledgeMgmt_docStatus_ready
  if (status === 'error') return langData.knowledgeMgmt_docStatus_error
  return langData.knowledgeMgmt_docStatus_processing
}

const fmtSize = (bytes: number) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

const fmtTime = (ts: number) => ts ? new Date(ts * 1000).toISOString().slice(0, 10) : '-'

const fileTypeIcon = (type: string) => {
  if (type?.startsWith('image')) return 'lucide:image'
  if (type?.startsWith('pdf')) return 'lucide:file-text'
  return 'lucide:file'
}

onMounted(() => { fetchDocs(); fetchKbName() })
</script>

<template>
  <div class="docs-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/settings/knowledge')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>{{ kbName || langData.knowledgeMgmt_title }}</h2>
    </div>

    <div v-if="docs.length > 0" class="page-toolbar">
      <div style="flex:1"></div>
      <el-button @click="openCreateInline">
        <Icon icon="lucide:edit-3" style="margin-right:4px" /> {{ langData.knowledgeMgmt_createInlineDoc }}
      </el-button>
      <el-button type="primary" @click="uploadVisible = true">
        <Icon icon="lucide:upload" style="margin-right:4px" /> {{ langData.knowledgeMgmt_uploadDoc }}
      </el-button>
    </div>

    <div v-if="docs.length === 0" class="empty-state">
      <Icon icon="lucide:file" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.knowledgeMgmt_noDoc }}</h3>
      <p class="empty-desc">{{ langData.knowledgeMgmt_noDocHint }}</p>
      <button class="btn-primary" @click="uploadVisible = true">
        <Icon icon="lucide:upload" /> {{ langData.knowledgeMgmt_uploadDoc }}
      </button>
      <button class="btn-primary" @click="openCreateInline" style="margin-top:12px">
        <Icon icon="lucide:edit-3" /> {{ langData.knowledgeMgmt_createInlineDoc }}
      </button>
    </div>

    <el-table v-else :data="docs" style="width:100%" :header-cell-style="headerCellStyle" stripe>
      <el-table-column :label="langData.tableHeaderName" min-width="200">
        <template #default="{ row }">
          <div style="display:flex;align-items:center;gap:6px">
            <Icon :icon="fileTypeIcon(row.fileType)" style="font-size:16px;flex-shrink:0;color:var(--el-text-color-secondary)" />
            <span>{{ row.fileName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="80" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="info" effect="plain">{{ row.fileType?.split('/')?.pop() || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.sourceType === 'inline' ? 'success' : 'info'" effect="plain">
            {{ row.sourceType === 'inline' ? langData.knowledgeMgmt_inlineDocBadge : langData.knowledgeMgmt_uploadDocBadge }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="90" align="right">
        <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="分块" width="70" align="center">
        <template #default="{ row }">{{ row.chunkCount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small" effect="plain">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="langData.tableHeaderCreateTime" width="110" align="center">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="langData.tableHeaderOp" width="200" align="center">
        <template #default="{ row }">
          <el-button link size="small" @click="openEditInline(row)">
            <el-tooltip :content="langData.knowledgeMgmt_editInlineDoc">
              <Icon icon="lucide:edit-3" style="font-size:15px" />
            </el-tooltip>
          </el-button>
          <el-divider direction="vertical" />
          <el-button link size="small" @click="reParse(row)" :disabled="row.status === 'processing'">
            <el-tooltip :content="langData.knowledgeMgmt_reParse">
              <Icon icon="lucide:refresh-cw" style="font-size:15px" />
            </el-tooltip>
          </el-button>
          <el-divider direction="vertical" />
          <el-button link size="small" @click="deleteDoc(row)">
            <el-tooltip :content="langData.btnDelete">
              <Icon icon="lucide:trash-2" style="font-size:15px;color:var(--el-color-danger)" />
            </el-tooltip>
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="uploadVisible" :title="langData.knowledgeMgmt_uploadDoc" width="420px" destroy-on-close>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        :on-change="(f: any) => { uploadFile = f.raw }"
        :on-remove="() => { uploadFile = null }"
      >
        <Icon icon="lucide:upload" :width="40" style="color:var(--el-text-color-secondary);margin-bottom:8px" />
        <div style="font-size:14px;color:var(--el-text-color-primary)">{{ langData.knowledgeMgmt_uploadDoc }}</div>
      </el-upload>
      <template #footer>
        <el-button @click="uploadVisible = false">{{ langData.btnCancel }}</el-button>
        <el-button type="primary" @click="handleUpload" :loading="uploading">{{ langData.btnConfirm }}</el-button>
      </template>
    </el-dialog>
  </div>

  <MarkdownEditorDialog
    v-model:visible="editorVisible"
    :kb-id="kbId"
    :edit-doc="editDoc"
    @saved="onDocSaved"
  />
</template>

<style scoped>
.docs-page { max-width: 1100px; margin: 0 auto; padding: 24px; }

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

/* dark mode */
html.dark .back-btn:hover { background: var(--el-fill-color); }
</style>
