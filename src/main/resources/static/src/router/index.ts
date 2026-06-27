import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/chat',
      name: 'Chat',
      component: () => import('../views/chat/ChatView.vue'),
    },
    {
      path: '/settings/mcp',
      name: 'MCPSettings',
      component: () => import('../views/settings/MCPSettings.vue'),
    },
    {
      path: '/settings/skills',
      name: 'SkillSettings',
      component: () => import('../views/settings/SkillSettings.vue'),
    },
    {
      path: '/settings/model',
      name: 'ModelSettings',
      component: () => import('../views/settings/ModelSettings.vue'),
    },
    {
      path: '/settings/workspace',
      name: 'WorkspaceSettings',
      component: () => import('../views/settings/WorkspaceSettings.vue'),
    },
    {
      path: '/settings/memory',
      name: 'MemorySettings',
      component: () => import('../views/settings/MemorySettings.vue'),
    },
    {
      path: '/settings/knowledge',
      name: 'KnowledgeSettings',
      component: () => import('../views/settings/KnowledgeSettings.vue'),
    },
    {
      path: '/settings/knowledge/:kbId/docs',
      name: 'KnowledgeDocs',
      component: () => import('../views/settings/KnowledgeDocs.vue'),
    },
    {
      path: '/settings/knowledge/:kbId/recall-test',
      name: 'KnowledgeRecallTest',
      component: () => import('../views/settings/KnowledgeRecallTest.vue'),
    },
    {
      path: '/settings/security',
      name: 'SecuritySettings',
      component: () => import('../views/settings/SecuritySettings.vue'),
    },
    {
      path: '/settings/tools',
      name: 'ToolControlSettings',
      component: () => import('../views/settings/ToolControlSettings.vue'),
    },
  ],
})

export default router
