<script lang="ts" setup>
import { computed, ref } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const convStore = useConversationsStore()
const settingsStore = useSettingsStore()

const openMenuId = ref<string | null>(null)
const renameId = ref<string | null>(null)
const renameText = ref('')

function deleteConversation(id: string) {
  axios({ url: '/conversations/delete', method: 'post', data: { id } })
    .then(() => { convStore.removeConversation(id); openMenuId.value = null })
    .catch(() => {})
}

function toggleMenu(id: string) {
  openMenuId.value = openMenuId.value === id ? null : id
}

function startRename(id: string, currentTitle: string) {
  renameId.value = id
  renameText.value = currentTitle
  openMenuId.value = null
}

function confirmRename(id: string) {
  const title = renameText.value.trim()
  if (title) {
    axios({ url: '/conversations/rename', method: 'post', data: { id, title } })
      .then(() => convStore.renameConversation(id, title))
  }
  renameId.value = null
}

function cancelRename() { renameId.value = null }
function closeMenu() { openMenuId.value = null }
function selectAndCloseSidebar(id: string) { convStore.selectConversation(id) }
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: convStore.sidebarCollapsed }">
    <div class="sidebar-header">
      <span class="logo">zephyr</span>
      <span class="spacer"></span>
      <button class="btn-icon" @click="convStore.toggleSidebar()" title="折叠侧边栏">
        <Icon icon="lucide:panel-left-close" />
      </button>
    </div>

    <div class="new-chat-btn" @click="$emit('newChat')">
      <Icon icon="lucide:square-pen" />
      <span>开启新对话</span>
    </div>

    <div class="sidebar-body">
      <div class="conv-list">
        <template v-for="group in convStore.groupedConversations" :key="group.label">
          <div class="conv-group">
            <div class="conv-group-label">{{ group.label }}</div>
            <div
              v-for="conv in group.conversations"
              :key="conv.id"
              class="conv-item"
              :class="{ active: convStore.currentId === conv.id }"
              @click="selectAndCloseSidebar(conv.id)"
            >
              <span v-if="renameId !== conv.id" class="title">{{ conv.title }}</span>
              <input
                v-else
                class="rename-input"
                v-model="renameText"
                @keydown.enter="confirmRename(conv.id)"
                @keydown.escape="cancelRename"
                @click.stop
                @blur="confirmRename(conv.id)"
              />

              <span class="menu-btn" @click.stop="toggleMenu(conv.id)">
                <Icon icon="lucide:ellipsis" />
                <div v-if="openMenuId === conv.id" class="conv-menu">
                  <div class="conv-menu-item" @click.stop="startRename(conv.id, conv.title)">
                    <Icon icon="lucide:pencil" />重命名
                  </div>
                  <div class="conv-menu-item danger" @click.stop="deleteConversation(conv.id)">
                    <Icon icon="lucide:trash-2" />删除
                  </div>
                </div>
              </span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="sidebar-footer">
      <div class="user-row" @click="$emit('openSettings')">
        <div class="avatar">A</div>
        <span class="uname">admin</span>
        <Icon icon="lucide:ellipsis" class="chevron-icon" />
      </div>
    </div>
    <Teleport to="body">
      <div v-if="openMenuId" class="menu-mask" @click="closeMenu"></div>
    </Teleport>
  </aside>
</template>

<script lang="ts">
export default { emits: ['newChat', 'openSettings'] }
</script>

<style scoped>
.sidebar { width: 280px; min-width: 280px; background: var(--el-bg-color); border-right: 1px solid var(--el-border-color); display: flex; flex-direction: column; transition: width 0.2s, min-width 0.2s; overflow: hidden; }
.sidebar.collapsed { width: 0; min-width: 0; border-right: none; }

.sidebar-header { padding: 12px 14px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--el-border-color); min-height: 52px; }
.logo { font-family: Georgia, 'Times New Roman', serif; font-size: 20px; color: var(--el-text-color-primary); letter-spacing: -0.3px; white-space: nowrap; }
.spacer { flex: 1; }
.btn-icon { width: 34px; height: 34px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; transition: background 0.15s, color 0.15s; flex-shrink: 0; }
.btn-icon:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }

.new-chat-btn { display: flex; align-items: center; justify-content: center; gap: 6px; margin: 4px 12px 8px; padding: 8px 12px; border-radius: 20px; cursor: pointer; transition: background 0.15s; font-size: 14px; color: var(--el-text-color-regular); background: rgba(0,0,0,0.03); border: 1px solid var(--el-border-color); }
.new-chat-btn:hover { background: var(--el-fill-color-light); }
.sidebar.collapsed .new-chat-btn { display: none; }

.sidebar-body { display: flex; flex-direction: column; flex: 1; overflow: hidden; }
.conv-list { flex: 1; overflow-y: auto; padding: 4px 10px; }
.conv-list::-webkit-scrollbar { width: 1px; }
.conv-list::-webkit-scrollbar-track { background: transparent; }
.conv-list::-webkit-scrollbar-thumb { background: transparent; }
.sidebar:hover .conv-list::-webkit-scrollbar-thumb { background: var(--el-border-color); }

.conv-group { margin-bottom: 4px; }
.conv-group-label { padding: 8px 12px 4px; font-size: 11px; font-weight: 500; color: var(--el-text-color-placeholder); text-transform: uppercase; letter-spacing: 1px; position: sticky; top: 0; background: var(--el-bg-color); z-index: 2; }
.conv-item { padding: 9px 12px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; gap: 8px; font-size: 14px; transition: background 0.1s; }
.conv-item:hover { background: var(--el-fill-color-light); }
.conv-item.active { background: var(--el-fill-color); }
.title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-text-color-primary); }
.rename-input { flex: 1; border: 1px solid var(--el-color-primary); border-radius: 4px; padding: 2px 6px; font-size: 14px; background: var(--el-bg-color); color: var(--el-text-color-primary); outline: none; font-family: inherit; }
.menu-btn { cursor: pointer; color: var(--el-text-color-secondary); flex-shrink: 0; font-size: 15px; position: relative; }

.conv-menu { position: absolute; right: 0; top: 100%; z-index: 100; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 8px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); min-width: 100px; padding: 4px; }
.conv-menu-item { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--el-text-color-regular); transition: background 0.1s; }
.conv-menu-item:hover { background: var(--el-fill-color-light); }
.conv-menu-item.danger { color: var(--el-color-danger); }
.conv-menu-item.danger:hover { background: rgba(198,69,69,0.08); }
.menu-mask { position: fixed; inset: 0; z-index: 99; }

.sidebar-footer { padding: 4px 10px; border-top: none; display: flex; align-items: center; position: relative; }
.sidebar-footer::before { content: ''; position: absolute; bottom: 100%; left: 0; right: 0; height: 48px; pointer-events: none; background: linear-gradient(to bottom, transparent, var(--el-bg-color) 80%); }
.sidebar.collapsed .sidebar-footer { display: none; }
.user-row { display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-radius: 8px; cursor: pointer; flex: 1; transition: background 0.15s; }
.user-row:hover { background: var(--el-fill-color-light); }
.avatar { width: 24px; height: 24px; border-radius: 50%; flex-shrink: 0; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 500; color: var(--el-color-primary); }
.uname { font-size: 13px; color: var(--el-text-color-secondary); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chevron-icon { color: var(--el-text-color-placeholder); font-size: 15px; flex-shrink: 0; }
</style>
