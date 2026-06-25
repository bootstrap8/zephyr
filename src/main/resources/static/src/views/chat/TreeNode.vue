<script lang="ts" setup>
import { Icon } from '@iconify/vue'

export interface DirNode {
  name: string
  path: string
  children: DirNode[]
  expanded: boolean
  loaded: boolean
  creating: boolean
  newDirName: string
  creatingError: string
}

const props = defineProps<{
  node: DirNode
  depth: number
}>()

const emit = defineEmits<{
  toggle: [node: DirNode]
  select: [node: DirNode]
  startCreate: [node: DirNode]
  cancelCreate: [node: DirNode]
  confirmCreate: [node: DirNode]
}>()

function hasChildren(node: DirNode): boolean {
  return node.children.length > 0 || !node.loaded
}
</script>

<template>
  <div class="tn-group">
    <div class="tn-row" :style="{ paddingLeft: depth * 16 + 'px' }">
      <button
        class="tn-toggle"
        :class="{ 'tn-toggle-hidden': !hasChildren(node) }"
        @click="emit('toggle', node)"
      >
        <Icon :icon="node.expanded ? 'lucide:chevron-down' : 'lucide:chevron-right'" width="14" />
      </button>
      <Icon icon="lucide:folder" class="tn-icon" />
      <span class="tn-name" @click="emit('select', node)">{{ node.name }}</span>
      <button class="tn-add" title="新建目录" @click="emit('startCreate', node)">
        <Icon icon="lucide:plus" width="14" />
      </button>
    </div>

    <!-- 新建目录输入框 -->
    <div v-if="node.creating" class="tn-create" :style="{ paddingLeft: (depth + 1) * 16 + 'px' }">
      <input
        v-model="node.newDirName"
        class="tn-create-input"
        placeholder="目录名"
        @keydown.enter="emit('confirmCreate', node)"
        @keydown.escape="emit('cancelCreate', node)"
      />
      <button class="tn-create-btn tn-create-ok" @click="emit('confirmCreate', node)">
        <Icon icon="lucide:check" width="14" />
      </button>
      <button class="tn-create-btn tn-create-cancel" @click="emit('cancelCreate', node)">
        <Icon icon="lucide:x" width="14" />
      </button>
      <span v-if="node.creatingError" class="tn-create-error">{{ node.creatingError }}</span>
    </div>

    <!-- 递归子节点 -->
    <div v-if="node.expanded">
      <TreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :depth="depth + 1"
        @toggle="(n: DirNode) => emit('toggle', n)"
        @select="(n: DirNode) => emit('select', n)"
        @start-create="(n: DirNode) => emit('startCreate', n)"
        @cancel-create="(n: DirNode) => emit('cancelCreate', n)"
        @confirm-create="(n: DirNode) => emit('confirmCreate', n)"
      />
    </div>
  </div>
</template>

<style scoped>
.tn-group { }
.tn-row { display: flex; align-items: center; gap: 4px; padding: 4px 0; border-radius: 4px; }
.tn-row:hover { background: var(--el-fill-color-light); }
.tn-toggle { width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; border-radius: 4px; flex-shrink: 0; padding: 0; }
.tn-toggle:hover { background: var(--el-fill-color); }
.tn-toggle-hidden { visibility: hidden; }
.tn-icon { font-size: 15px; color: var(--el-color-primary); flex-shrink: 0; }
.tn-name { flex: 1; font-size: 13px; color: var(--el-text-color-primary); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 2px 0; }
.tn-name:hover { color: var(--el-color-primary); }
.tn-add { width: 22px; height: 22px; display: none; align-items: center; justify-content: center; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; border-radius: 4px; flex-shrink: 0; padding: 0; }
.tn-row:hover .tn-add { display: flex; }
.tn-add:hover { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.tn-create { display: flex; align-items: center; gap: 4px; padding: 4px 0; }
.tn-create-input { flex: 1; padding: 4px 8px; border: 1px solid var(--el-color-primary); border-radius: 4px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 12px; outline: none; font-family: inherit; }
.tn-create-btn { width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border: none; border-radius: 4px; cursor: pointer; flex-shrink: 0; }
.tn-create-ok { background: var(--el-color-primary); color: #fff; }
.tn-create-ok:hover { background: var(--el-color-primary-dark-2); }
.tn-create-cancel { background: transparent; color: var(--el-text-color-secondary); }
.tn-create-cancel:hover { background: var(--el-fill-color-light); }
.tn-create-error { font-size: 11px; color: var(--el-color-danger); white-space: nowrap; }
</style>
