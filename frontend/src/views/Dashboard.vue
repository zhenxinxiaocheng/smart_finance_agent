<template>
  <div class="dashboard">
    <div class="page-header">
      <div class="header-text">
        <h1 class="page-title">财务概览</h1>
        <p class="page-subtitle">掌握你的财务状况，做出更明智的决策</p>
      </div>
      <div class="header-period">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          value-format="YYYY-MM-DD"
          size="default"
          @change="fetchData"
        />
      </div>
    </div>

    <div class="stats-grid">
      <div class="stat-card" v-for="card in statCards" :key="card.label">
        <div class="stat-icon" :class="card.iconClass">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <span class="stat-label">{{ card.label }}</span>
          <div class="stat-value-row">
            <span class="stat-value" :class="card.valueClass">{{ card.prefix }}{{ card.formatted }}</span>
          </div>
          <span class="stat-desc">{{ card.desc }}</span>
        </div>
      </div>
    </div>

    <div class="charts-grid">
      <div class="chart-card">
        <div class="chart-header">
          <h3 class="chart-title">
            <span class="title-dot" style="background: #1e3a8a;"></span>
            支出分类分布
          </h3>
          <div class="chart-legend">
            <span v-for="item in categoryData" :key="item.name" class="legend-item">
              <span class="legend-dot" :style="{ background: item.color }"></span>
              {{ item.name }}
            </span>
          </div>
        </div>
        <div class="chart-body">
          <v-chart :option="pieOption" class="chart pie-chart" autoresize />
          <div class="pie-summary" v-if="categoryData.length > 0">
            <div class="pie-total-label">总支出</div>
            <div class="pie-total-value">{{ formatMoney(pieTotal) }}</div>
          </div>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <h3 class="chart-title">
            <span class="title-dot" style="background: #22d3ee;"></span>
            月度收支趋势
          </h3>
          <div class="chart-tabs">
            <span
              v-for="t in trendTabs"
              :key="t.value"
              class="tab-item"
              :class="{ active: activeTab === t.value }"
              @click="switchTab(t.value)"
            >{{ t.label }}</span>
          </div>
        </div>
        <div class="chart-body">
          <v-chart :option="lineOption" class="chart line-chart" autoresize />
        </div>
      </div>
    </div>

    <div class="recent-section">
      <div class="section-header">
        <h3 class="section-title">最近交易</h3>
        <el-button text class="view-all" @click="$router.push('/transactions')">
          查看全部
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div class="recent-list">
        <div v-for="tx in recentTransactions" :key="tx.id" class="tx-item">
          <div class="tx-left">
            <div class="tx-icon" :class="tx.type === 'INCOME' ? 'income' : 'expense'">
              <el-icon :size="18">
                <Top v-if="tx.type === 'INCOME'" />
                <Bottom v-else />
              </el-icon>
            </div>
            <div class="tx-info">
              <span class="tx-category">{{ tx.category }}</span>
              <span class="tx-date">{{ tx.transactionDate }}</span>
            </div>
          </div>
          <div class="tx-amount" :class="tx.type === 'INCOME' ? 'income' : 'expense'">
            {{ tx.type === 'INCOME' ? '+' : '-' }}{{ formatMoney(tx.amount) }}
          </div>
        </div>
        <div v-if="recentTransactions.length === 0" class="tx-empty">
          <el-icon :size="48"><Coin /></el-icon>
          <p>暂无交易记录</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listTransactionsAPI, categorySummaryAPI } from '../api/transaction'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { PieChart, LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([PieChart, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const now = new Date()
const firstDay = new Date(now.getFullYear(), now.getMonth(), 1)
const lastDay = new Date(now.getFullYear(), now.getMonth() + 1, 0)
const fmt = d => d.toISOString().split('T')[0]

const dateRange = ref([fmt(firstDay), fmt(lastDay)])
const categoryData = ref([])
const recentTransactions = ref([])
const pieTotal = ref(0)
const activeTab = ref('30d')

const trendTabs = [
  { label: '近30天', value: '30d' },
  { label: '近90天', value: '90d' },
  { label: '全年', value: '1y' }
]

const colorPalette = [
  '#1e3a8a', '#3b82f6', '#22d3ee', '#10b981',
  '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899',
  '#14b8a6', '#f97316', '#6366f1', '#84cc16'
]

const statCards = computed(() => {
  const records = recentTransactions.value
  let totalIncome = 0, totalExpense = 0
  for (const r of records) {
    if (r.type === 'INCOME') totalIncome += Number(r.amount)
    else totalExpense += Number(r.amount)
  }
  const balance = totalIncome - totalExpense
  const savingsRate = totalIncome > 0 ? ((balance / totalIncome) * 100).toFixed(1) : '0.0'

  return [
    {
      label: '总收入', icon: 'Top', iconClass: 'income-icon',
      value: totalIncome, prefix: '¥', formatted: totalIncome.toFixed(2),
      valueClass: 'income-text', desc: '本月累计收入'
    },
    {
      label: '总支出', icon: 'Bottom', iconClass: 'expense-icon',
      value: totalExpense, prefix: '¥', formatted: totalExpense.toFixed(2),
      valueClass: 'expense-text', desc: '本月累计支出'
    },
    {
      label: '结余', icon: 'Money', iconClass: 'balance-icon',
      value: balance, prefix: '¥', formatted: Math.abs(balance).toFixed(2),
      valueClass: balance >= 0 ? 'balance-positive' : 'balance-negative',
      desc: balance >= 0 ? '财务状况良好' : '支出超出收入'
    },
    {
      label: '储蓄率', icon: 'TrendCharts', iconClass: 'savings-icon',
      value: savingsRate, prefix: '', formatted: savingsRate + '%',
      valueClass: 'savings-text', desc: `共 ${records.length} 条记录`
    }
  ]
})

const pieOption = computed(() => {
  const colors = colorPalette.slice(0, categoryData.value.length)
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p) => {
        return `<strong>${p.name}</strong><br/>金额：¥${Number(p.value).toFixed(2)}<br/>占比：${p.percent}%`
      },
      backgroundColor: 'rgba(255,255,255,0.95)',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      textStyle: { color: '#0f172a', fontSize: 13 }
    },
    series: [{
      type: 'pie',
      radius: ['55%', '80%'],
      center: ['50%', '48%'],
      avoidLabelOverlap: true,
      padAngle: 2,
      itemStyle: {
        borderRadius: 4,
        borderColor: '#fff',
        borderWidth: 2
      },
      label: {
        show: true,
        formatter: '{d}%',
        fontSize: 12,
        fontWeight: 600,
        color: '#475569'
      },
      labelLine: {
        length: 8,
        length2: 12,
        smooth: true
      },
      emphasis: {
        scale: true,
        label: { show: true, fontWeight: 'bold' }
      },
      data: categoryData.value.length > 0
        ? categoryData.value.map((item, i) => ({
            ...item,
            itemStyle: { color: colors[i] }
          }))
        : [{ value: 1, name: '暂无数据', itemStyle: { color: '#e2e8f0' } }]
    }]
  }
})

const lineOption = computed(() => {
  const { dates, income, expense } = buildLineData()
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,0.95)',
      borderColor: '#e2e8f0',
      borderWidth: 1,
      textStyle: { color: '#0f172a', fontSize: 12 },
      formatter: (params) => {
        const [i, e] = params
        return `<strong>${i.axisValue}</strong><br/>
          <span style="color:#1e3a8a;">● 收入：¥${Number(i.value).toFixed(2)}</span><br/>
          <span style="color:#ef4444;">● 支出：¥${Number(e.value).toFixed(2)}</span>`
      }
    },
    legend: {
      data: ['收入', '支出'],
      right: 0,
      top: 0,
      textStyle: { color: '#64748b', fontSize: 12 },
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '30px',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' } },
      axisLabel: {
        color: '#94a3b8',
        fontSize: 11,
        formatter: (v) => v >= 10000 ? (v / 10000) + 'w' : v
      }
    },
    series: [
      {
        name: '收入',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5, color: '#1e3a8a' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(30, 58, 138, 0.15)' },
              { offset: 1, color: 'rgba(30, 58, 138, 0)' }
            ]
          }
        },
        itemStyle: { color: '#1e3a8a' },
        data: income
      },
      {
        name: '支出',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5, color: '#ef4444' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(239, 68, 68, 0.12)' },
              { offset: 1, color: 'rgba(239, 68, 68, 0)' }
            ]
          }
        },
        itemStyle: { color: '#ef4444' },
        data: expense
      }
    ]
  }
})

const allTransactions = ref([])

async function fetchData() {
  const [start, end] = dateRange.value
  try {
    const [catRes, listRes] = await Promise.all([
      categorySummaryAPI({ startDate: start, endDate: end }),
      listTransactionsAPI({ page: 1, size: 100, startDate: start, endDate: end })
    ])
    if (catRes.code === 200) {
      categoryData.value = catRes.data.map(item => ({
        name: item.category,
        value: Number(item.total)
      }))
      pieTotal.value = categoryData.value.reduce((s, i) => s + i.value, 0)
    }
    if (listRes.code === 200) {
      recentTransactions.value = (listRes.data.records || []).slice(0, 10)
      allTransactions.value = listRes.data.records || []
    }
  } catch (e) {
    console.error('Dashboard fetch error:', e)
  }
}

function switchTab(tab) {
  activeTab.value = tab
}

function buildLineData() {
  const days = activeTab.value === '30d' ? 30 : activeTab.value === '90d' ? 90 : 365
  const dates = []
  const income = []
  const expense = []
  const now = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    dates.push(`${d.getMonth() + 1}/${d.getDate()}`)
    income.push(0)
    expense.push(0)
  }
  for (const r of allTransactions.value) {
    const d = new Date(r.transactionDate)
    const diff = Math.round((now - d) / (1000 * 60 * 60 * 24))
    const idx = days - 1 - diff
    if (idx >= 0 && idx < days) {
      if (r.type === 'INCOME') income[idx] += Number(r.amount)
      else expense[idx] += Number(r.amount)
    }
  }
  return { dates, income, expense }
}

function formatMoney(val) {
  return '¥' + Number(val || 0).toFixed(2)
}

onMounted(fetchData)
</script>

<style scoped>
.dashboard { max-width: 1400px; margin: 0 auto; }

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 28px;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text);
  margin: 0;
}

.page-subtitle {
  font-size: 14px;
  color: var(--text-muted);
  margin-top: 4px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 24px;
}

.stat-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 24px;
  display: flex;
  gap: 16px;
  box-shadow: var(--shadow);
  transition: var(--transition);
}

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.income-icon { background: #eef2ff; color: #1e3a8a; }
.expense-icon { background: #fef2f2; color: #ef4444; }
.balance-icon { background: #ecfdf5; color: #10b981; }
.savings-icon { background: #eff6ff; color: #3b82f6; }

.stat-body { flex: 1; min-width: 0; }

.stat-label {
  font-size: 13px;
  color: var(--text-muted);
  font-weight: 500;
  display: block;
  margin-bottom: 4px;
}

.stat-value-row {
  margin-bottom: 2px;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  font-feature-settings: 'tnum';
}

.income-text { color: #1e3a8a; }
.expense-text { color: #ef4444; }
.balance-positive { color: #10b981; }
.balance-negative { color: #ef4444; }
.savings-text { color: #3b82f6; }

.stat-desc {
  font-size: 12px;
  color: var(--text-muted);
}

.charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-bottom: 24px;
}

.chart-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px 0;
}

.chart-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.chart-legend {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.chart-tabs {
  display: flex;
  gap: 4px;
  background: var(--bg);
  padding: 3px;
  border-radius: 8px;
}

.tab-item {
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-muted);
  border-radius: 6px;
  cursor: pointer;
  transition: var(--transition);
}

.tab-item.active {
  background: #fff;
  color: var(--text);
  box-shadow: var(--shadow-sm);
}

.chart-body {
  position: relative;
  padding: 16px;
}

.chart {
  height: 300px;
}

.pie-chart { height: 320px; }

.pie-summary {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
  pointer-events: none;
}

.pie-total-label {
  font-size: 12px;
  color: var(--text-muted);
}

.pie-total-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--text);
}

.recent-section {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border);
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}

.view-all {
  font-size: 13px;
  color: var(--primary-light);
}

.recent-list { padding: 4px 0; }

.tx-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 24px;
  transition: var(--transition);
}

.tx-item:hover { background: var(--primary-50); }

.tx-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.tx-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.tx-icon.income { background: #eef2ff; color: #1e3a8a; }
.tx-icon.expense { background: #fef2f2; color: #ef4444; }

.tx-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tx-category { font-size: 14px; font-weight: 600; color: var(--text); }
.tx-date { font-size: 12px; color: var(--text-muted); }

.tx-amount {
  font-size: 16px;
  font-weight: 700;
  font-feature-settings: 'tnum';
}

.tx-amount.income { color: #1e3a8a; }
.tx-amount.expense { color: #ef4444; }

.tx-empty {
  text-align: center;
  padding: 40px;
  color: var(--text-muted);
}

.tx-empty p {
  margin-top: 12px;
  font-size: 14px;
}

@media (max-width: 1200px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .charts-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .stats-grid { grid-template-columns: 1fr; }
  .page-header { flex-direction: column; gap: 12px; }
}
</style>
