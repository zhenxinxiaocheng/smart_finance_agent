<template>
  <div class="layout">
    <aside class="sidebar" :class="{ collapsed: isCollapse }">
      <div class="sidebar-brand">
        <div class="brand-icon">
          <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
            <rect width="32" height="32" rx="8" fill="#1e3a8a"/>
            <path d="M16 8c-4.42 0-8 3.13-8 7 0 2.97 2.16 5.45 5.2 6.37l-2.11 3.54a.5.5 0 00.43.76l2.37-.01 1.96-2.99c.37.05.75.08 1.15.08 4.42 0 8-3.13 8-7s-3.58-7-8-7zm-2.5 9.5a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm5 0a1.5 1.5 0 110-3 1.5 1.5 0 010 3z" fill="#22d3ee" opacity="0.9"/>
          </svg>
        </div>
        <transition name="fade">
          <span v-if="!isCollapse" class="brand-name">智财Agent</span>
        </transition>
      </div>

      <nav class="sidebar-nav">
        <div
          v-for="item in navItems"
          :key="item.path"
          class="nav-item"
          :class="{ active: activeMenu === item.path }"
          @click="navigate(item.path)"
        >
          <div class="nav-icon">
            <el-icon :size="20"><component :is="item.icon" /></el-icon>
          </div>
          <transition name="fade">
            <span v-if="!isCollapse" class="nav-label">{{ item.label }}</span>
          </transition>
          <div v-if="!isCollapse && item.badge" class="nav-badge">{{ item.badge }}</div>
        </div>
      </nav>

      <div class="sidebar-footer">
        <div class="nav-item logout" @click="handleLogout">
          <div class="nav-icon">
            <el-icon :size="20"><SwitchButton /></el-icon>
          </div>
          <transition name="fade">
            <span v-if="!isCollapse" class="nav-label">退出登录</span>
          </transition>
        </div>
      </div>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <div class="topbar-left">
          <button class="collapse-btn" @click="isCollapse = !isCollapse">
            <el-icon :size="20"><Fold v-if="!isCollapse" /><Expand v-else /></el-icon>
          </button>
          <div class="page-title-area">
            <span class="page-title">{{ currentTitle }}</span>
          </div>
        </div>
        <div class="topbar-right">
          <div class="user-menu" @click="handleLogout">
            <div class="user-avatar">{{ authStore.username?.charAt(0)?.toUpperCase() || 'U' }}</div>
            <span class="user-name">{{ authStore.username }}</span>
          </div>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const isCollapse = ref(false)
const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta?.title || '统计')

const navItems = [
  { path: '/bill-import', label: '账单导入', icon: 'UploadFilled' },
  { path: '/statistics', label: '统计', icon: 'Odometer' },
  { path: '/transactions', label: '消费记录', icon: 'List' },
  { path: '/profile', label: '财务画像', icon: 'User' },
  { path: '/skills', label: 'Agent 技能', icon: 'Operation' },
  { path: '/chat', label: '智能助手', icon: 'ChatDotSquare' }
]

function navigate(path) {
  router.push(path)
}

function handleLogout() {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    authStore.logout()
    router.push('/login')
  }).catch(() => {})
}
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  background: var(--bg);
}

.sidebar {
  width: 240px;
  min-width: 240px;
  background: var(--bg-sidebar);
  display: flex;
  flex-direction: column;
  transition: width 0.25s cubic-bezier(0.4, 0, 0.2, 1), min-width 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  position: relative;
  z-index: 10;
}

.sidebar.collapsed {
  width: 64px;
  min-width: 64px;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  min-height: 72px;
}

.brand-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.brand-name {
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 1px;
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-footer {
  padding: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.55);
  transition: all 0.2s;
  position: relative;
  white-space: nowrap;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.9);
}

.nav-item.active {
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: #3b82f6;
  border-radius: 0 3px 3px 0;
}

.nav-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
}

.nav-label {
  font-size: 14px;
  font-weight: 500;
}

.nav-badge {
  margin-left: auto;
  background: var(--accent);
  color: var(--bg-sidebar);
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 10px;
}

.logout {
  margin-top: auto;
}

.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: 64px;
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  background: var(--bg-header);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 5;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.collapse-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: #fff;
  cursor: pointer;
  color: var(--text-secondary);
  transition: var(--transition);
}

.collapse-btn:hover {
  border-color: var(--primary-lighter);
  color: var(--primary);
  box-shadow: var(--shadow-sm);
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-menu {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 12px 6px 6px;
  border-radius: var(--radius);
  cursor: pointer;
  transition: var(--transition);
}

.user-menu:hover {
  background: var(--bg);
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--primary), var(--primary-light));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}

.user-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
}

.content {
  flex: 1;
  padding: 28px;
  overflow-y: auto;
  overflow-x: hidden;
}
</style>
