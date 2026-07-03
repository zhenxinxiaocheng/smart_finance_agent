<template>
  <MainLayoutShadcn />
</template>

<script setup>
import MainLayoutShadcn from './MainLayoutShadcn.vue'
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
  border-right: 1px solid var(--border);
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
  border-bottom: 1px solid var(--border);
  min-height: 72px;
}

.brand-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.brand-name {
  color: var(--text);
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
  border-top: 1px solid var(--border);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius);
  cursor: pointer;
  color: var(--text-light);
  transition: all 0.2s;
  position: relative;
  white-space: nowrap;
}

.nav-item:hover {
  background: var(--primary-50);
  color: var(--text);
}

.nav-item.active {
  background: var(--primary-surface);
  color: var(--text);
}

.nav-item.active::before {
  content: '';
  position: absolute;
  left: -8px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 20px;
  background: var(--primary);
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
  color: #fff;
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
  background: var(--primary);
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
