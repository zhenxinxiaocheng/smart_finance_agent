import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { rememberInvestmentDetailPath } from '../lib/investmentNavigation'

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
    redirect: '/chat'
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayoutShadcn.vue'),
    meta: { auth: true },
    children: [
      {
        path: 'stocks',
        name: 'InvestmentAnalysis',
        component: () => import('../views/InvestmentAnalysis.vue'),
        meta: { title: '投资分析' }
      },
      {
        path: 'stocks/:assetId',
        name: 'InvestmentAssetDetail',
        component: () => import('../views/InvestmentAssetDetail.vue'),
        meta: { title: '投资详情' }
      },
      {
        path: 'quant',
        component: () => import('../views/quant-workbench/Workbench.vue'),
        meta: { title: '量化工作台' },
        children: [
          { path: '', name: 'QuantStrategies', component: () => import('../views/quant-workbench/Strategies.vue') },
          { path: 'strategies/:id', name: 'QuantStrategyDetail', component: () => import('../views/quant-workbench/StrategyDetail.vue') },
          { path: 'universes', name: 'QuantUniverses', component: () => import('../views/quant-workbench/Universes.vue') },
          { path: 'factors', name: 'QuantFactors', component: () => import('../views/quant-workbench/Factors.vue') },
          { path: 'tasks', name: 'QuantTasks', component: () => import('../views/quant-workbench/Tasks.vue') },
          { path: 'deployments', name: 'QuantDeployments', component: () => import('../views/quant-workbench/Deployments.vue') },
        ]
      },
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
        meta: { title: '新对话' }
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
  const requiresAuth = to.matched.some(record => record.meta.auth)
  const guestOnly = to.matched.some(record => record.meta.guest)
  if (requiresAuth && !authStore.isLoggedIn) {
    next('/login')
  } else if (guestOnly && authStore.isLoggedIn) {
    next('/chat')
  } else {
    next()
  }
})

router.afterEach(to => {
  rememberInvestmentDetailPath(to.path)
})

export default router
