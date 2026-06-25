import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Conversation, ConvGroup } from '@/types/chat'

export const useConversationsStore = defineStore('conversations', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<string | null>(null)
  const sidebarCollapsed = ref(false)

  const current = computed(() =>
    conversations.value.find(c => c.id === currentId.value) ?? null
  )

  const groupedConversations = computed((): ConvGroup[] => {
    const now = Math.floor(Date.now() / 1000)
    const daySec = 86400
    const groups: ConvGroup[] = []

    const today: Conversation[] = []
    const week: Conversation[] = []
    const month: Conversation[] = []
    const older: Map<string, Conversation[]> = new Map()

    for (const c of conversations.value) {
      const diff = now - c.updatedAt
      if (diff < daySec) {
        today.push(c)
      } else if (diff < 7 * daySec) {
        week.push(c)
      } else if (diff < 30 * daySec) {
        month.push(c)
      } else {
        const d = new Date(c.updatedAt * 1000)
        const key = `${d.getFullYear()}年${d.getMonth() + 1}月`
        if (!older.has(key)) older.set(key, [])
        older.get(key)!.push(c)
      }
    }

    if (today.length) groups.push({ label: '今天', conversations: today })
    if (week.length) groups.push({ label: '7 天内', conversations: week })
    if (month.length) groups.push({ label: '30 天内', conversations: month })
    for (const [key, convs] of older) {
      groups.push({ label: key, conversations: convs })
    }

    return groups
  })

  function setConversations(list: Conversation[]) {
    conversations.value = list
  }

  function selectConversation(id: string) {
    currentId.value = id
  }

  function addConversation(conv: Conversation) {
    conversations.value.unshift(conv)
    currentId.value = conv.id
  }

  function removeConversation(id: string) {
    conversations.value = conversations.value.filter(c => c.id !== id)
    if (currentId.value === id) {
      currentId.value = conversations.value[0]?.id ?? null
    }
  }

  function renameConversation(id: string, title: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.title = title
  }

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  return {
    conversations, currentId, current, groupedConversations, sidebarCollapsed,
    setConversations, selectConversation, addConversation, removeConversation, renameConversation,
    toggleSidebar
  }
})
