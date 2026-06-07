<template>
  <div class="statistics">
    <!-- ==================== 统计类型切换 ==================== -->
    <div class="tab-switch-bar">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        class="tab-btn"
        :class="{ active: activeTab === tab.value }"
        @click="switchTab(tab.value)"
      >
        <el-icon :size="16"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
    </div>

    <!-- ==================== 软切换内容区（带过渡） ==================== -->
    <transition name="slide-fade" mode="out-in">
      <!-- 日常统计 -->
      <div v-if="activeTab === 'daily'" :key="'daily-' + lastRefresh" class="tab-content">
        <DailyStats />
      </div>

      <!-- 月统计 -->
      <div v-else-if="activeTab === 'monthly'" :key="'monthly-' + lastRefresh" class="tab-content">
        <MonthlyStats />
      </div>

      <!-- 年统计 -->
      <div v-else :key="'yearly-' + lastRefresh" class="tab-content">
        <YearlyStats />
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import DailyStats from './statistics/DailyStats.vue'
import MonthlyStats from './statistics/MonthlyStats.vue'
import YearlyStats from './statistics/YearlyStats.vue'

const tabs = [
  { label: '日常', value: 'daily', icon: 'TrendCharts' },
  { label: '月统计', value: 'monthly', icon: 'DataAnalysis' },
  { label: '年统计', value: 'yearly', icon: 'PieChart' }
]

const activeTab = ref('daily')
const lastRefresh = ref(Date.now())

function switchTab(value) {
  activeTab.value = value
  lastRefresh.value = Date.now() // 强制刷新组件
}
</script>

<style scoped>
.statistics {
  max-width: 1400px;
  margin: 0 auto;
}

/* ===== 切换按钮组 ===== */
.tab-switch-bar {
  display: flex;
  gap: 4px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 4px;
  margin-bottom: 28px;
  box-shadow: var(--shadow);
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 24px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--text-muted);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  white-space: nowrap;
}

.tab-btn:hover {
  color: var(--text-secondary);
  background: var(--bg);
}

.tab-btn.active {
  background: var(--primary);
  color: #fff;
  box-shadow: 0 2px 8px rgba(30, 58, 138, 0.25);
}

/* ===== 过渡动画 ===== */
.slide-fade-enter-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.slide-fade-leave-active {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.slide-fade-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.slide-fade-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

.tab-content {
  min-height: 600px;
}

@media (max-width: 768px) {
  .tab-btn {
    padding: 10px 16px;
    font-size: 13px;
    gap: 4px;
  }
}
</style>