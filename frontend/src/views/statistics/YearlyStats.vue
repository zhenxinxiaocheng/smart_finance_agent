<template>
  <div class="yearly-stats">
    <!-- ===== 年份选择器 ===== -->
    <div class="year-picker-bar">
      <button class="quick-btn" @click="selectCurrentYear">今年</button>
      <button class="quick-btn" @click="selectPrevYear">去年</button>
      <div class="divider"></div>
      <div class="year-scroll" ref="scrollRef">
        <button
          v-for="y in yearOptions"
          :key="y"
          class="year-chip"
          :class="{ active: selectedYear === y }"
          @click="selectedYear = y"
        >{{ y }}</button>
      </div>
    </div>

    <!-- ===== 核心指标 ===== -->
    <div class="kpi-section" v-if="!loading">
      <div class="kpi-card">
        <span class="kpi-label">年支出</span>
        <span class="kpi-value expense">{{ formatMoney(yearExpense) }}</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">年收入</span>
        <span class="kpi-value income">{{ formatMoney(yearIncome) }}</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">年结余</span>
        <span class="kpi-value" :class="yearBalance >= 0 ? 'positive' : 'negative'">
          {{ formatMoney(yearBalance) }}
        </span>
      </div>
    </div>

    <!-- ===== 图表区域 ===== -->
    <div class="charts-grid-3">
      <!-- 月度收支统计 -->
      <div class="chart-block">
        <div class="chart-block-header">
          <h4>月度收支统计</h4>
        </div>
        <v-chart :option="monthlyIncomeExpenseOption" class="chart-inner" autoresize />
      </div>

      <!-- 资产走势 -->
      <div class="chart-block">
        <div class="chart-block-header">
          <h4>资产走势</h4>
        </div>
        <v-chart :option="assetTrendOption" class="chart-inner" autoresize />
      </div>

      <!-- 收支占比 -->
      <div class="chart-block">
        <div class="chart-block-header">
          <div class="chart-header-row">
            <h4>收支占比</h4>
            <div class="chart-toggle">
              <button
                class="toggle-btn"
                :class="{ active: ratioType === 'expense' }"
                @click="ratioType = 'expense'"
              >支出分类</button>
              <button
                class="toggle-btn"
                :class="{ active: ratioType === 'income' }"
                @click="ratioType = 'income'"
              >收入分类</button>
            </div>
          </div>
        </div>
        <div class="chart-body">
          <v-chart :option="ratioOption" class="chart-inner" autoresize />
          <div class="category-legend">
            <div
              v-for="item in categoryRatioData"
              :key="item.name"
              class="cat-legend-item"
            >
              <span class="cat-dot" :style="{ background: item.color }"></span>
              <span class="cat-name">{{ item.name }}</span>
              <span class="cat-value">{{ formatMoney(item.value) }}</span>
              <span class="cat-pct">{{ item.percent }}%</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 月度明细表格 ===== -->
    <div class="table-block">
      <div class="table-header">
        <h4>月度明细</h4>
        <el-input
          v-model="tableFilter"
          placeholder="筛选月份..."
          :prefix-icon="Search"
          size="small"
          clearable
          style="width: 200px;"
        />
      </div>
      <el-table
        :data="filteredMonthlyData"
        stripe
        style="width: 100%"
        size="default"
        :default-sort="{ prop: 'month', order: 'ascending' }"
        max-height="420"
      >
        <el-table-column prop="month" label="月份" sortable width="120" />
        <el-table-column prop="income" label="收入" sortable align="right">
          <template #default="{ row }">
            <span class="cell-income">{{ formatMoney(row.income) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="expense" label="支出" sortable align="right">
          <template #default="{ row }">
            <span class="cell-expense">{{ formatMoney(row.expense) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="balance" label="结余" sortable align="right">
          <template #default="{ row }">
            <span :class="row.balance >= 0 ? 'cell-positive' : 'cell-negative'">
              {{ formatMoney(row.balance) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="count" label="笔数" sortable align="center" width="80" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { listTransactionsAPI } from '../../api/transaction'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, LineChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const scrollRef = ref(null)
const tableFilter = ref('')
const loading = ref(false)

// 年份选项：当前年份往前推5年
const currentYear = new Date().getFullYear()
const yearOptions = []
for (let y = currentYear; y >= currentYear - 4; y--) {
  yearOptions.push(y)
}
const selectedYear = ref(currentYear)

const allTransactions = ref([])

const yearIncome = ref(0)
const yearExpense = ref(0)
const yearBalance = computed(() => yearIncome.value - yearExpense.value)

const ratioType = ref('expense')

const colorPalette = [
  '#1e3a8a', '#3b82f6', '#22d3ee', '#10b981',
  '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899',
  '#14b8a6', '#f97316', '#6366f1', '#84cc16'
]

// 月度明细
const monthlyData = computed(() => {
  const map = {}
  for (let m = 1; m <= 12; m++) {
    const key = `${selectedYear.value}-${String(m).padStart(2, '0')}`
    map[key] = { month: key, income: 0, expense: 0, balance: 0, count: 0 }
  }
  for (const r of allTransactions.value) {
    const key = r.transactionDate.substring(0, 7)
    if (map[key]) {
      if (r.type === 'INCOME') map[key].income += Number(r.amount)
      else map[key].expense += Number(r.amount)
      map[key].count++
    }
  }
  const result = Object.values(map)
  result.forEach(r => { r.balance = r.income - r.expense })
  return result
})

const filteredMonthlyData = computed(() => {
  if (!tableFilter.value) return monthlyData.value
  return monthlyData.value.filter(d => d.month.includes(tableFilter.value))
})

// 月度收支统计图表
const monthlyIncomeExpenseOption = computed(() => ({
  tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#e2e8f0', borderWidth: 1, textStyle: { color: '#0f172a', fontSize: 12 } },
  grid: { left: '3%', right: '4%', bottom: '3%', top: '10px', containLabel: true },
  xAxis: { type: 'category', data: monthlyData.value.map(d => d.month.substring(5) + '月'), axisLabel: { color: '#94a3b8', fontSize: 10 }, axisTick: { show: false } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' } }, axisLabel: { color: '#94a3b8', fontSize: 11 } },
  series: [
    { name: '收入', type: 'bar', barWidth: 10, itemStyle: { color: '#1e3a8a', borderRadius: [3, 3, 0, 0] }, data: monthlyData.value.map(d => d.income) },
    { name: '支出', type: 'bar', barWidth: 10, itemStyle: { color: '#ef4444', borderRadius: [3, 3, 0, 0] }, data: monthlyData.value.map(d => d.expense) }
  ]
}))

// 资产走势 (累积结余)
const assetTrendOption = computed(() => {
  let cumSum = 0
  const balances = monthlyData.value.map(d => { cumSum += d.balance; return cumSum })
  return {
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#e2e8f0', borderWidth: 1, textStyle: { color: '#0f172a', fontSize: 12 } },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '10px', containLabel: true },
    xAxis: { type: 'category', data: monthlyData.value.map(d => d.month.substring(5) + '月'), axisLabel: { color: '#94a3b8', fontSize: 10 }, axisTick: { show: false } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9', type: 'dashed' } }, axisLabel: { color: '#94a3b8', fontSize: 11 } },
    series: [{
      name: '累积结余', type: 'line', smooth: true, symbol: 'none',
      lineStyle: { width: 2, color: '#10b981' },
      areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(16,185,129,0.15)' }, { offset: 1, color: 'rgba(16,185,129,0)' }] } },
      data: balances
    }]
  }
})

// 分类占比数据（根据 ratioType 切换：支出分类 / 收入分类）
const categoryRatioData = computed(() => {
  const map = {}
  for (const r of allTransactions.value) {
    const matchType = ratioType.value === 'expense' ? 'EXPENSE' : 'INCOME'
    if (r.type !== matchType) continue
    const cat = r.category || '其他'
    if (!map[cat]) map[cat] = 0
    map[cat] += Number(r.amount)
  }
  const entries = Object.entries(map).sort((a, b) => b[1] - a[1])
  const total = entries.reduce((s, [, v]) => s + v, 0)
  return entries.map(([name, value], i) => ({
    name,
    value,
    color: colorPalette[i % colorPalette.length],
    percent: total > 0 ? ((value / total) * 100).toFixed(1) : '0.0'
  }))
})

// 收支占比 (分类饼图)
const ratioOption = computed(() => {
  const data = categoryRatioData.value
  const chartData = data.length > 0
    ? data.map(d => ({ ...d, itemStyle: { color: d.color } }))
    : [{ value: 1, name: '暂无数据', itemStyle: { color: '#e2e8f0' } }]
  return {
    tooltip: { trigger: 'item', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#e2e8f0', borderWidth: 1, textStyle: { color: '#0f172a', fontSize: 12 }, formatter: (p) => `<strong>${p.name}</strong><br/>¥${Number(p.value).toFixed(2)} (${p.percent}%)` },
    legend: { show: data.length > 0, bottom: 0, textStyle: { fontSize: 11, color: '#64748b' }, icon: 'circle', itemWidth: 8, itemHeight: 8 },
    series: [{
      type: 'pie', radius: ['45%', '68%'], center: ['50%', '38%'], padAngle: 2,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: data.length > 0, formatter: '{d}%', fontSize: 11, fontWeight: 600 },
      data: chartData
    }]
  }
})

async function fetchYearData() {
  loading.value = true
  const startStr = `${selectedYear.value}-01-01`
  const endStr = `${selectedYear.value}-12-31`
  try {
    const res = await listTransactionsAPI({ page: 1, size: 5000, startDate: startStr, endDate: endStr })
    if (res.code === 200) {
      allTransactions.value = res.data.records || []
      let inc = 0, exp = 0
      for (const r of allTransactions.value) {
        if (r.type === 'INCOME') inc += Number(r.amount)
        else exp += Number(r.amount)
      }
      yearIncome.value = inc
      yearExpense.value = exp
    }
  } catch (e) {
    console.error('Yearly stats fetch error:', e)
  } finally {
    loading.value = false
  }
}

function selectCurrentYear() { selectedYear.value = currentYear }
function selectPrevYear() { selectedYear.value = currentYear - 1 }

watch(selectedYear, () => {
  fetchYearData()
  if (scrollRef.value) {
    const activeEl = scrollRef.value.querySelector('.year-chip.active')
    if (activeEl) {
      activeEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
    }
  }
})

function formatMoney(val) {
  return '¥' + Number(val || 0).toFixed(2)
}

onMounted(fetchYearData)
</script>

<style scoped>
.yearly-stats { display: flex; flex-direction: column; gap: 20px; }

/* ===== 年份选择器 ===== */
.year-picker-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 8px 12px;
  box-shadow: var(--shadow);
}

.quick-btn {
  padding: 6px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--transition);
  white-space: nowrap;
  flex-shrink: 0;
}

.quick-btn:hover { border-color: var(--primary-lighter); color: var(--primary); }

.divider { width: 1px; height: 24px; background: var(--border); flex-shrink: 0; }

.year-scroll {
  flex: 1;
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 2px 4px;
  scroll-behavior: smooth;
}

.year-scroll::-webkit-scrollbar { height: 2px; }
.year-scroll::-webkit-scrollbar-thumb { background: #e2e8f0; border-radius: 2px; }

.year-chip {
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: var(--bg);
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--transition);
  white-space: nowrap;
  flex-shrink: 0;
}

.year-chip:hover { background: #e2e8f0; color: var(--text); }
.year-chip.active { background: var(--primary); color: #fff; border-color: var(--primary); }

/* ===== 核心指标 ===== */
.kpi-section {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.kpi-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 24px;
  box-shadow: var(--shadow);
  display: flex; flex-direction: column; gap: 8px;
  transition: var(--transition);
}

.kpi-card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
.kpi-label { font-size: 13px; color: var(--text-muted); font-weight: 500; }
.kpi-value { font-size: 30px; font-weight: 700; font-feature-settings: 'tnum'; }
.kpi-value.expense { color: #ef4444; }
.kpi-value.income { color: #1e3a8a; }
.kpi-value.positive { color: #10b981; }
.kpi-value.negative { color: #ef4444; }

/* ===== 图表三列 ===== */
.charts-grid-3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.chart-block {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.chart-block-header {
  padding: 16px 20px 0;
  font-size: 14px; font-weight: 600; color: var(--text);
}

.chart-header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-toggle {
  display: flex;
  gap: 2px;
  background: var(--bg);
  padding: 2px;
  border-radius: 6px;
}

.toggle-btn {
  padding: 4px 12px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--transition);
}

.toggle-btn.active {
  background: #fff;
  color: var(--text);
  box-shadow: var(--shadow-sm);
}

.chart-body {
  padding: 12px 16px 16px;
}

.chart-inner { height: 240px; }

/* ===== 分类图例 ===== */
.category-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  padding: 8px 4px 0;
  border-top: 1px solid var(--border-light);
  margin-top: 4px;
}

.cat-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}

.cat-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.cat-name { font-weight: 500; }
.cat-value { color: var(--text-muted); }
.cat-pct { font-weight: 600; color: var(--text); }

/* ===== 表格 ===== */
.table-block {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  overflow: hidden;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  font-size: 14px; font-weight: 600; color: var(--text);
}

.cell-income { color: #1e3a8a; font-weight: 600; }
.cell-expense { color: #ef4444; font-weight: 600; }
.cell-positive { color: #10b981; font-weight: 600; }
.cell-negative { color: #ef4444; font-weight: 600; }

@media (max-width: 1200px) {
  .charts-grid-3 { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 768px) {
  .kpi-section { grid-template-columns: 1fr; }
  .charts-grid-3 { grid-template-columns: 1fr; }
}
</style>