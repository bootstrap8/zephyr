<script lang="ts" setup>
import { ref } from 'vue'
import axios from '@/network'
import type { ConfirmActionEvent } from '@/types/chat'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

const props = defineProps<{
  visible: boolean
  event: ConfirmActionEvent | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const loading = ref(false)

function respond(action: 'allow' | 'deny') {
  if (!props.event) return
  loading.value = true
  axios({
    url: '/chat/confirm',
    method: 'POST',
    data: { confirmId: props.event.confirmId, action }
  })
    .then(() => { emit('close') })
    .catch(() => { emit('close') })
    .finally(() => { loading.value = false })
}

const riskLabel = (rule: string): string => {
  switch (rule) {
    case 'HARD_BLOCK': return langData.value?.confirmRiskHard || 'HARD_BLOCK'
    case 'SOFT_BLOCK': return langData.value?.confirmRiskSoft || 'SOFT_BLOCK'
    default: return rule
  }
}

const riskColor = (rule: string): string => {
  switch (rule) {
    case 'HARD_BLOCK': return 'var(--el-color-danger)'
    case 'SOFT_BLOCK': return 'var(--el-color-warning)'
    default: return 'var(--el-color-info)'
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="langData?.confirmTitle || '操作确认'"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    width="480px"
    @close="emit('close')"
  >
    <div v-if="event" class="confirm-content">
      <div class="confirm-risk" :style="{ color: riskColor(event.rule) }">
        {{ riskLabel(event.rule) }}
      </div>

      <div class="confirm-tool">
        <span class="label">{{ langData?.confirmTool || '工具' }}：</span>
        <code>{{ event.toolName }}</code>
      </div>

      <div class="confirm-input">
        <span class="label">{{ langData?.confirmParams || '参数' }}：</span>
        <pre>{{ JSON.stringify(event.toolInput, null, 2) }}</pre>
      </div>

      <div class="confirm-reason">
        <span class="label">{{ langData?.confirmRiskDesc || '风险说明' }}：</span>
        <span>{{ event.ruleDetail }}</span>
      </div>
    </div>

    <template #footer>
      <div class="confirm-footer">
        <el-button
          type="danger"
          :loading="loading"
          @click="respond('deny')"
        >
          {{ langData?.confirmDeny || '拒绝' }}
        </el-button>
        <el-button
          type="primary"
          :loading="loading"
          @click="respond('allow')"
        >
          {{ langData?.confirmAllow || '允许本次' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.confirm-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.confirm-risk {
  font-size: 16px;
  font-weight: 700;
}
.confirm-tool code {
  background: var(--el-fill-color-light);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
  word-break: break-all;
}
.confirm-input pre {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
  max-height: 160px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
.confirm-reason {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  word-break: break-all;
}
.label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.confirm-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
