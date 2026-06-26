<script lang="ts" setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import { getLangData } from '@/i18n/locale'

const router = useRouter()
const settingsStore = useSettingsStore()
const langData = getLangData()

const adminChecked = ref(false)

const activeTab = ref('SHELL_ALLOWED')
const loading = ref(false)
const showDialog = ref(false)
const editId = ref<string | null>(null)
const dialogValue = ref('')
const dialogDesc = ref('')
const selectedRows = ref<any[]>([])
const currentPage = ref(1)
const pageSize = ref(10)
const statusFilter = ref('all')

const COMMAND_TABS = ['SHELL_ALLOWED', 'DEFAULT_ALLOW']
const TAB_INFO = [
  { name: 'SHELL_ALLOWED', labelKey: 'securityMgmt_tabShellAllowed' },
  { name: 'DEFAULT_ALLOW', labelKey: 'securityMgmt_tabDefaultAllow' },
  { name: 'HARD_BLOCK', labelKey: 'securityMgmt_tabHardBlock' },
  { name: 'SOFT_BLOCK', labelKey: 'securityMgmt_tabSoftBlock' },
]

const isPatternTab = computed(() => !COMMAND_TABS.includes(activeTab.value))

const rules = computed(() => settingsStore.securityRules[activeTab.value] || [])

const filteredRules = computed(() => {
  if (!isPatternTab.value || statusFilter.value === 'all') return rules.value
  const enabled = statusFilter.value === 'enabled'
  return rules.value.filter((r: any) => !!r.enabled === enabled)
})

const pagedRules = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRules.value.slice(start, start + pageSize.value)
})

const hasSelection = computed(() => selectedRows.value.length > 0)

const valueLabel = computed(() =>
  isPatternTab.value ? langData.securityMgmt_patternLabel : langData.securityMgmt_commandLabel
)

const valuePlaceholder = computed(() =>
  isPatternTab.value ? langData.securityMgmt_valuePlaceholder_pattern : langData.securityMgmt_valuePlaceholder_cmd
)

const dialogTitle = computed(() =>
  editId.value ? langData.dialogTitleEdit : langData.dialogTitleAdd
)

const headerCellStyle = () => ({
  backgroundColor: 'var(--el-fill-color-light)',
  color: 'var(--el-text-color-primary)',
  fontWeight: 600,
  whiteSpace: 'nowrap'
})

function tabLabel(tab: { name: string; labelKey: string }): string {
  return (langData as any)[tab.labelKey] || tab.name
}

onMounted(async () => {
  await settingsStore.loadUserInfo()
  if (!settingsStore.isAdmin) {
    router.replace('/chat')
    return
  }
  adminChecked.value = true
  loadRules()
})

watch(activeTab, () => {
  selectedRows.value = []
  currentPage.value = 1
  statusFilter.value = 'all'
  if (!settingsStore.securityRules[activeTab.value]) loadRules()
})

async function loadRules() {
  loading.value = true
  await settingsStore.loadSecurityRules(activeTab.value)
  loading.value = false
}

async function refreshRules() {
  selectedRows.value = []
  currentPage.value = 1
  await loadRules()
}

function onSelectionChange(rows: any[]) {
  selectedRows.value = rows
}

function openAdd() {
  editId.value = null
  dialogValue.value = ''
  dialogDesc.value = ''
  showDialog.value = true
}

function openEdit(rule: any) {
  editId.value = rule.id
  dialogValue.value = rule.ruleValue || ''
  dialogDesc.value = rule.description || ''
  showDialog.value = true
}

async function saveRule() {
  if (!dialogValue.value.trim()) return
  const val = dialogValue.value.trim()
  const desc = dialogDesc.value.trim()
  if (editId.value) {
    await settingsStore.updateSecurityRule(activeTab.value, editId.value, val, desc)
  } else {
    await settingsStore.addSecurityRule(activeTab.value, val, desc)
  }
  showDialog.value = false
  currentPage.value = 1
}

function confirmDelete(rule: any) {
  ElMessageBox.confirm(
    langData.securityMgmt_confirmDeleteMsg,
    langData.confirmDelete,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => doDelete(rule)).catch(() => {})
}

async function doDelete(rule: any) {
  if (!rule.id) return
  await settingsStore.deleteSecurityRule(activeTab.value, rule.id)
  selectedRows.value = []
  // 如果当前页删空了，回退一页
  const totalAfter = rules.value.length
  const maxPage = Math.max(1, Math.ceil(totalAfter / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
}

function confirmBatchDelete() {
  const count = selectedRows.value.length
  ElMessageBox.confirm(
    langData.securityMgmt_confirmBatchDeleteMsg.replace('{count}', String(count)),
    langData.confirmDelete,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => doBatchDelete()).catch(() => {})
}

async function doBatchDelete() {
  const ids = selectedRows.value.map((r: any) => r.id)
  await settingsStore.batchDeleteSecurityRules(activeTab.value, ids)
  selectedRows.value = []
  currentPage.value = 1
}

async function onToggle(rule: any, val: any) {
  if (!rule.id) return
  await settingsStore.toggleSecurityRule(activeTab.value, rule.id, val ? 1 : 0)
}
</script>

<template>
  <div v-if="adminChecked" class="security-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.securityMgmt_title }}</h1>
      </div>
      <div class="header-actions">
        <button v-if="rules.length > 0" class="btn-primary" @click="openAdd">
          <Icon icon="lucide:plus" /> {{ langData.securityMgmt_addRule }}
        </button>
      </div>
    </div>
    <p class="subtitle">{{ langData.securityMgmt_subtitle }}</p>

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="tab in TAB_INFO" :key="tab.name" :label="tabLabel(tab)" :name="tab.name">
        <div v-if="loading" class="loading-state">{{ langData.inputArea_loading }}</div>

        <div v-else-if="rules.length === 0" class="empty-state">
          <Icon icon="lucide:shield-off" width="48" style="color: var(--el-text-color-placeholder)" />
          <h3 class="empty-title">{{ langData.securityMgmt_noRules }}</h3>
          <button class="btn-primary" @click="openAdd">
            <Icon icon="lucide:plus" /> {{ langData.securityMgmt_addFirstRule }}
          </button>
        </div>

        <div v-else class="table-wrapper">
          <div class="table-toolbar">
            <el-tooltip :content="langData.securityMgmt_refresh" placement="top">
              <el-button link @click="refreshRules">
                <Icon icon="lucide:refresh-cw" />
              </el-button>
            </el-tooltip>
            <el-divider direction="vertical" />
            <el-tooltip :content="langData.securityMgmt_batchDelete" placement="top">
              <el-button link :disabled="!hasSelection" @click="confirmBatchDelete">
                <Icon icon="lucide:trash-2" />
              </el-button>
            </el-tooltip>
            <template v-if="isPatternTab">
              <el-divider direction="vertical" />
              <el-select v-model="statusFilter" size="small" style="width: 100px" @change="currentPage = 1">
                <el-option label="全部" value="all" />
                <el-option :label="langData.securityMgmt_enabled" value="enabled" />
                <el-option :label="langData.securityMgmt_disabled" value="disabled" />
              </el-select>
            </template>
          </div>

          <el-table
            :data="pagedRules"
            style="width: 100%"
            :header-cell-style="headerCellStyle"
            stripe
            @selection-change="onSelectionChange"
          >
            <el-table-column type="selection" width="42" />

            <el-table-column type="index" :label="langData.securityMgmt_index" width="60" />

            <el-table-column :label="valueLabel" prop="ruleValue" min-width="200">
              <template #default="{ row }">
                <span class="mono-text">{{ row.ruleValue }}</span>
              </template>
            </el-table-column>

            <el-table-column :label="langData.tableHeaderDesc" prop="description" min-width="200">
              <template #default="{ row }">
                <span class="desc-text">{{ row.description || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column
              v-if="isPatternTab"
              :label="langData.securityMgmt_statusLabel"
              width="90"
              align="center"
            >
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? langData.securityMgmt_enabled : langData.securityMgmt_disabled }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column :label="langData.tableHeaderOp" width="170" align="center">
              <template #default="{ row }">
                <el-switch
                  v-if="isPatternTab"
                  :model-value="!!row.enabled"
                  size="small"
                  style="margin-right: 6px"
                  @change="(val: any) => onToggle(row, val)"
                />
                <el-tooltip v-if="isPatternTab" :content="langData.btnEdit">
                  <el-button circle size="small" @click="openEdit(row)">
                    <Icon icon="lucide:pencil" />
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="langData.btnDelete">
                  <el-button circle size="small" @click="confirmDelete(row)">
                    <Icon icon="lucide:trash-2" />
                  </el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrapper">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              :total="filteredRules.length"
              layout="total, sizes, prev, pager, next"
              small
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showDialog" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="valueLabel" required>
          <el-input v-model="dialogValue" :placeholder="valuePlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.tableHeaderDesc">
          <el-input v-model="dialogDesc" :placeholder="langData.securityMgmt_descPlaceholder" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">{{ langData.btnCancel }}</el-button>
        <el-button type="primary" @click="saveRule">{{ editId ? langData.btnSave : langData.btnAdd }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.security-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
.header-actions { display: flex; align-items: center; gap: 8px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }

.loading-state { text-align: center; padding: 80px 24px; font-size: 14px; color: var(--el-text-color-placeholder); }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }

.table-toolbar { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.table-wrapper { margin-top: 0; }

.mono-text { font-family: "JetBrains Mono", monospace; font-size: 13px; color: var(--el-text-color-primary); }
.desc-text { font-size: 13px; color: var(--el-text-color-secondary); }

.pagination-wrapper { display: flex; justify-content: flex-end; margin-top: 12px; }

:deep(.el-table) { border-radius: 8px; overflow: hidden; }

html.dark .back-btn:hover { background: var(--el-fill-color); }

html.dark .table-toolbar :deep(.el-button.is-circle) {
  background-color: var(--el-fill-color-light) !important;
  border-color: var(--el-border-color) !important;
}
</style>
