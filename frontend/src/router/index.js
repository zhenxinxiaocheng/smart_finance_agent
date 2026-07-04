import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginShadcn.vue'),
    meta: { guest: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/RegisterShadcn.vue'),
    meta: { guest: true }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayoutShadcn.vue'),
    meta: { auth: true },
    redirect: '/statistics',
    children: [
      {
        path: 'statistics',
        name: 'Statistics',
        component: () => import('../views/Statistics.vue'),
        meta: { title: '统计' }
      },
      {
        path: 'transactions',
        name: 'Transactions',
        component: () => import('../views/Transaction.vue'),
        meta: { title: '消费记录' }
      },
      {
        path: 'profile',
        name: 'FinancialProfile',
        component: () => import('../views/FinancialProfile.vue'),
        meta: { title: '财务画像' }
      },
      {
        path: 'skills',
        name: 'AgentSkills',
        component: () => import('../views/AgentSkills.vue'),
        meta: { title: 'Agent 技能' }
      },
      {
        path: 'agent-audit',
        name: 'AgentAudit',
        component: () => import('../views/AgentAudit.vue'),
        meta: { title: 'Agent 审计' }
      },
      {
        path: 'schedules',
        name: 'AgentSchedules',
        component: () => import('../views/AgentSchedules.vue'),
        meta: { title: '周期任务' }
      },
      {
        path: 'reflections',
        name: 'AgentReflections',
        component: () => import('../views/AgentReflections.vue'),
        meta: { title: 'Agent 反思' }
      },
      {
        path: 'pending-actions',
        name: 'AgentPendingActions',
        component: () => import('../views/AgentPendingActions.vue'),
        meta: { title: '待确认动作' }
      },
      {
        path: 'bill-import',
        name: 'BillImport',
        component: () => import('../views/BillImport.vue'),
        meta: { title: '账单导入' }
      },
      {
        path: 'chat',
        name: 'Chat',
        component: () => import('../views/ChatView.vue'),
        meta: { title: '智能助手' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  if (to.meta.auth && !authStore.isLoggedIn) {
    next('/login')
  } else if (to.meta.guest && authStore.isLoggedIn) {
    next('/statistics')
  } else {
    next()
  }
})

export default router
