<template>
  <div class="daily-stats">
    <!-- ===== 近七日统计 ===== -->
    <div class="section-block">
      <div class="section-header">
        <h3 class="section-title">
          <span class="title-dot" style="background: var(--primary);"></span>
          近七日统计
        </h3>
        <span class="section-date-range">{{ sevenDayRange }}</span>
      </div>

      <!-- 统计卡片 -->
      <div class="stats-cards-row">
        <div class="stat-mini-card" v-for="card in dailyCards" :key="card.label">
          <div class="mini-icon" :class="card.iconClass">
            <el-icon :size="18"><component :is="card.icon" /></el-icon>
          </div>
          <div class="mini-body">
            <span class="mini-label">{{ card.label }}</span>
            <span class="mini-value" :class="card.valueClass">{{ card.formatted }}</span>
          </div>
        </div>
      </div>

      <!-- 七日收支趋势图 -->
      <div class="chart-card">
        <div class="chart-header">
          <h4 class="chart-title">七日收支趋势</h4>
        </div>
        <div class="chart-body">
          <v-chart :option="sevenDayChartOption" class="chart-bar" autoresize />
        </div>
      </div>
    </div>

    <!-- ===== 资产汇总 ===== -->
    <div class="section-block">
      <div class="section-header">
        <h3 class="section-title">
          <span class="title-dot" style="background: var(--accent);"></span>
          资产汇总
        </h3>
      </div>

      <div class="assets-grid">
        <div class="asset-card highlight">
          <div class="asset-card-header">
            <span class="asset-label">总资产</span>
            <el-icon :size="20" class="asset-icon total"><Money /></el-icon>
          </div>
          <span class="asset-value total-value">{{ formatMoney(assetSummary.totalAssets) }}</span>
          <span class="asset-desc">当前累计净资产</span>
        </div>
        <div class="asset-card">
          <div class="asset-card-header">
            <span class="asset-label">总收入</span>
            <el-icon :size="20" class="asset-icon income"><Top /></el-icon>
          </div>
          <span class="asset-value income-value">{{ formatMoney(assetSummary.totalIncome) }}</span>
          <span class="asset-desc">期间累计收入</span>
        </div>
        <div class="asset-card">
          <div class="asset-card-header">
            <span class="asset-label">总支出</span>
            <el-icon :size="20" class="asset-icon expense"><Bottom /></el-icon>
          </div>
          <span class="asset-value expense-value">{{ formatMoney(assetSummary.totalExpense) }}</span>
          <span class="asset-desc">期间累计支出</span>
        </div>
        <div class="asset-card">
          <div class="asset-card-header">
            <span class="asset-label">交易笔数</span>
            <el-icon :size="20" class="asset-icon count"><List /></el-icon>
          </div>
          <span class="asset-value count-value">{{ assetSummary.transactionCount }}</span>
          <span class="asset-desc">期间交易总笔数</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listTransactionsAPI } from '../../api/transaction'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, TitleComponent, TooltipComponent, GridComponent, CanvasRenderer])

const sevenDayData = ref({ income: [], expense: [], labels: [] })
const assetSummary = ref({ totalAssets: 0, totalIncome: 0, totalExpense: 0, transactionCount: 0 })

const sevenDayRange = computed(() => {
  const now = new Date()
  const end = new Date(now)
  end.setHours(0, 0, 0, 0)
  const start = new Date(end)
  start.setDate(start.getDate() - 6)
  const fmt = d => `${d.getMonth() + 1}/${d.getDate()}`
  return `${fmt(start)} - ${fmt(end)}`
})

const dailyCards = computed(() => {
  const income7 = sevenDayData.value.income.reduce((s, v) => s + v, 0)
  const expense7 = sevenDayData.value.expense.reduce((s, v) => s + v, 0)
  const balance = income7 - expense7
  return [
    { label: '收入', icon: 'Top', iconClass: 'income', valueClass: 'income-text', formatted: formatMoney(income7) },
    { label: '支出', icon: 'Bottom', iconClass: 'expense', valueClass: 'expense-text', formatted: formatMoney(expense7) },
    { label: '结余', icon: 'Money', iconClass: 'balance', valueClass: balance >= 0 ? 'balance-positive' : 'balance-negative', formatted: formatMoney(balance) }
  ]
})

const sevenDayChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(255,255,255,0.95)',
    borderColor: '#e2e8f0',
    borderWidth: 1,
    textStyle: { color: '#0f172a', fontSize: 12 }
  },
  grid: { left: '3%', right: '4%', bottom: '3%', top: '20px', containLabel: true },
  xAxis: {
    type: 'category',
    data: sevenDayData.value.labels,
    axisLine: { lineStyle: { color: '#e2e8f0' } },
    axisTick: { show: false },
    axisLabel: { color: '#94a3b8', fontSize: 11 }
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' } },
    axisLabel: { color: '#94a3b8', fontSize: 11 }
  },
  series: [
    {
      name: '收入', type: 'bar', barWidth: 14,
      itemStyle: { color: '#1e3a8a', borderRadius: [4, 4, 0, 0] },
      data: sevenDayData.value.income
    },
    {
      name: '支出', type: 'bar', barWidth: 14,
      itemStyle: { color: '#ef4444', borderRadius: [4, 4, 0, 0] },
      data: sevenDayData.value.expense
    }
  ]
}))

async function fetchData() {
  const now = new Date()
  const end = new Date(now)
  end.setHours(0, 0, 0, 0)
  const start = new Date(end)
  start.setDate(start.getDate() - 6)
  
  const fmtDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const fmtLabel = d => `${d.getMonth() + 1}/${d.getDate()}`

  try {
    const res = await listTransactionsAPI({ page: 1, size: 1000, startDate: fmtDate(start), endDate: fmtDate(end) })
    if (res.code === 200) {
      const records = res.data.records || []
      assetSummary.value.transactionCount = res.data.total || records.length

      // 初始化7天数据
      const days = []
      const income = []
      const expense = []
      const dayKeys = []
      
      for (let i = 0; i <= 6; i++) {
        const d = new Date(start)
        d.setDate(d.getDate() + i)
        days.push(fmtLabel(d))
        dayKeys.push(fmtDate(d))
        income.push(0)
        expense.push(0)
      }

      let totalIncome = 0, totalExpense = 0
      for (const r of records) {
        const idx = dayKeys.indexOf(r.transactionDate)
        if (idx !== -1) {
          if (r.type === 'INCOME') {
            income[idx] += Number(r.amount)
            totalIncome += Number(r.amount)
          } else {
            expense[idx] += Number(r.amount)
            totalExpense += Number(r.amount)
          }
        }
      }

      sevenDayData.value = { labels: days, income, expense }
      assetSummary.value.totalIncome = totalIncome
      assetSummary.value.totalExpense = totalExpense
      assetSummary.value.totalAssets = totalIncome - totalExpense
    }
  } catch (e) {
    console.error('Daily stats fetch error:', e)
  }
}

function formatMoney(val) {
  return '¥' + Number(val || 0).toFixed(2)
}

onMounted(fetchData)
</script>

<style scoped>
.daily-stats { display: flex; flex-direction: column; gap: 24px; }

/* ===== 区块 ===== */
.section-block {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px 0;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.title-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; flex-shrink: 0; }

.section-date-range { font-size: 13px; color: var(--text-muted); }

/* ===== 统计小卡片 ===== */
.stats-cards-row {
  display: flex;
  gap: 16px;
  padding: 20px 24px;
}

.stat-mini-card {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg);
  transition: var(--transition);
}

.stat-mini-card:hover {
  border-color: var(--primary-lighter);
  box-shadow: var(--shadow-sm);
}

.mini-icon {
  width: 42px; height: 42px;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}

.mini-icon.income { background: #eef2ff; color: #1e3a8a; }
.mini-icon.expense { background: #fef2f2; color: #ef4444; }
.mini-icon.balance { background: #ecfdf5; color: #10b981; }

.mini-body { display: flex; flex-direction: column; gap: 2px; }
.mini-label { font-size: 12px; color: var(--text-muted); font-weight: 500; }
.mini-value { font-size: 20px; font-weight: 700; font-feature-settings: 'tnum'; }

.income-text { color: #1e3a8a; }
.expense-text { color: #ef4444; }
.balance-positive { color: #10b981; }
.balance-negative { color: #ef4444; }

/* ===== 图表卡片 ===== */
.chart-card { border-top: 1px solid var(--border); }
.chart-header { padding: 16px 24px 0; }
.chart-title { font-size: 14px; font-weight: 600; color: var(--text); margin: 0; }
.chart-body { padding: 12px 16px 16px; }
.chart-bar { height: 280px; }

/* ===== 资产汇总 ===== */
.assets-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 20px 24px 24px;
}

.asset-card {
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: var(--transition);
  display: flex; flex-direction: column; gap: 8px;
}

.asset-card:hover { box-shadow: var(--shadow-sm); border-color: var(--primary-lighter); }
.asset-card.highlight { background: linear-gradient(135deg, #eef2ff 0%, #fff 100%); border-color: var(--primary-lighter); }

.asset-card-header { display: flex; justify-content: space-between; align-items: center; }
.asset-label { font-size: 13px; color: var(--text-muted); font-weight: 500; }
.asset-value { font-size: 24px; font-weight: 700; font-feature-settings: 'tnum'; }
.asset-desc { font-size: 11px; color: var(--text-muted); }

.total-value { color: var(--primary); }
.income-value { color: #1e3a8a; }
.expense-value { color: #ef4444; }
.count-value { color: var(--accent-dark); }

.asset-icon.total { color: var(--primary); }
.asset-icon.income { color: #1e3a8a; }
.asset-icon.expense { color: #ef4444; }
.asset-icon.count { color: var(--accent-dark); }

@media (max-width: 1000px) {
  .assets-grid { grid-template-columns: repeat(2, 1fr); }
  .stats-cards-row { flex-direction: column; }
}

@media (max-width: 600px) {
  .assets-grid { grid-template-columns: 1fr; }
}
</style>