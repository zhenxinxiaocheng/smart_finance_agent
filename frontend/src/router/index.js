import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { guest: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue'),
    meta: { guest: true }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
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
