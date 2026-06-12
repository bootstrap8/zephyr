import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Workspace } from '@/types/chat'
import axios from '@/network'
import { useConversationsStore } from './conversations'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const currentId = ref<string | null>(null)

  const current = computed(() =>
    workspaces.value.find(w => w.id === currentId.value) ?? null
  )

  function setWorkspaces(list: Workspace[]) {
    workspaces.value = list
  }

  function selectWorkspace(id: string | null) {
    currentId.value = id
    const convStore = useConversationsStore()
    if (id && convStore.currentId) {
      axios({ url: '/conversations/update-workspace', method: 'post',
        data: { conversationId: convStore.currentId, workspaceId: id } })
    }
  }

  function addWorkspace(ws: Workspace) {
    workspaces.value.unshift(ws)
    currentId.value = ws.id
  }

  function removeWorkspace(id: string) {
    workspaces.value = workspaces.value.filter(w => w.id !== id)
    if (currentId.value === id) currentId.value = null
  }

  return { workspaces, currentId, current, setWorkspaces, selectWorkspace, addWorkspace, removeWorkspace }
})
