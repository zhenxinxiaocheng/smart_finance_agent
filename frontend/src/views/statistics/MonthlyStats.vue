<template>
  <div class="monthly-stats flex flex-col gap-[var(--app-section-gap)]">
    <!-- ===== 月份选择器 ===== -->
    <div class="flex flex-wrap items-center gap-2 rounded-xl border bg-card px-3 py-2 shadow-sm">
      <Button variant="outline" size="sm" @click="selectCurrentMonth">本月</Button>
      <Button variant="outline" size="sm" @click="selectPrevMonth">上月</Button>
      <Separator orientation="vertical" class="mx-1 hidden h-6 sm:block" />
      <div class="flex min-w-0 flex-1 gap-1 overflow-x-auto py-0.5" ref="scrollRef">
        <Button
          v-for="m in monthOptions"
          :key="m.value"
          :variant="selectedMonth === m.value ? 'default' : 'ghost'"
          size="sm"
          :class="['shrink-0', selectedMonth === m.value ? 'month-chip-active' : '']"
          @click="selectedMonth = m.value"
        >{{ m.label }}</Button>
      </div>
    </div>

    <!-- ===== 核心指标 ===== -->
    <div class="grid gap-3 md:grid-cols-3" v-if="!loading">
      <Card v-for="item in monthlyKpis" :key="item.label">
        <CardHeader class="flex flex-row items-start justify-between gap-3">
          <div>
            <CardDescription>{{ item.label }}</CardDescription>
            <CardTitle class="mt-1 text-2xl tabular-nums" :class="item.valueClass">{{ item.value }}</CardTitle>
            <p class="mt-2 text-xs text-muted-foreground">{{ item.desc }}</p>
          </div>
          <div class="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <component :is="item.icon" />
          </div>
        </CardHeader>
      </Card>
    </div>

    <!-- ===== 图表区域 ===== -->
    <div class="grid gap-4 xl:grid-cols-3">
      <!-- 收支统计图 -->
      <Card>
        <CardHeader>
          <CardTitle>收支统计</CardTitle>
        </CardHeader>
        <CardContent>
          <v-chart :option="incomeExpenseOption" class="chart-inner" autoresize />
        </CardContent>
      </Card>

      <!-- 资产走势图 -->
      <Card>
        <CardHeader>
          <CardTitle>资产走势</CardTitle>
        </CardHeader>
        <CardContent>
          <v-chart :option="assetTrendOption" class="chart-inner" autoresize />
        </CardContent>
      </Card>

      <!-- 收支占比图 -->
      <Card>
        <CardHeader class="flex flex-row items-center justify-between">
          <CardTitle>收支占比</CardTitle>
          <div class="flex rounded-lg bg-muted p-1">
            <Button
              :variant="ratioType === 'expense' ? 'secondary' : 'ghost'"
              size="xs"
              @click="ratioType = 'expense'"
            >支出分类</Button>
            <Button
              :variant="ratioType === 'income' ? 'secondary' : 'ghost'"
              size="xs"
              @click="ratioType = 'income'"
            >收入分类</Button>
          </div>
        </CardHeader>
        <CardContent>
          <v-chart :option="ratioOption" class="chart-inner" autoresize />
          <div class="flex flex-wrap gap-2 border-t pt-3">
            <div
              v-for="item in categoryRatioData"
              :key="item.name"
              class="flex items-center gap-2 rounded-md bg-muted px-2 py-1 text-xs"
            >
              <span class="size-2 rounded-full" :style="{ background: item.color }"></span>
              <span class="font-medium">{{ item.name }}</span>
              <span class="text-muted-foreground">{{ formatMoney(item.value) }}</span>
              <span>{{ item.percent }}%</span>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>

    <!-- ===== 每日明细表格 ===== -->
    <Card>
      <CardHeader class="flex flex-row items-center justify-between">
        <div>
          <CardTitle>每日明细</CardTitle>
          <CardDescription>按日期汇总收入、支出、结余和交易笔数</CardDescription>
        </div>
        <div class="relative w-52">
          <Search class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" data-icon="inline-start" />
          <Input
            class="pl-8"
            v-model="tableFilter"
            placeholder="筛选日期..."
          />
        </div>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>日期</TableHead>
              <TableHead class="text-right">收入</TableHead>
              <TableHead class="text-right">支出</TableHead>
              <TableHead class="text-right">结余</TableHead>
              <TableHead class="text-center">笔数</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="row in filteredDailyData" :key="row.date">
              <TableCell>{{ row.date }}</TableCell>
              <TableCell class="text-right font-medium text-primary">{{ formatMoney(row.income) }}</TableCell>
              <TableCell class="text-right font-medium text-destructive">{{ formatMoney(row.expense) }}</TableCell>
              <TableCell class="text-right font-medium" :class="row.balance >= 0 ? 'text-primary' : 'text-destructive'">
                {{ formatMoney(row.balance) }}
              </TableCell>
              <TableCell class="text-center">{{ row.count }}</TableCell>
            </TableRow>
            <TableRow v-if="filteredDailyData.length === 0">
              <TableCell colspan="5" class="h-24 text-center text-muted-foreground">暂无匹配数据</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ArrowDownRight, ArrowUpRight, Search, WalletCards } from '@lucide/vue'
import { transactionStatisticsAPI } from '../../api/transaction'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAppearance } from '@/composables/useAppearance'
import { getChartTheme } from '@/lib/chartTheme'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, LineChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const scrollRef = ref(null)
const tableFilter = ref('')
const loading = ref(false)
const { mode, themeColor } = useAppearance()

// 生成近12个月的选项
const now = new Date()
const monthOptions = []
const selectedMonth = ref('')
for (let i = 11; i >= 0; i--) {
  const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
  const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  monthOptions.push({ label: value, value })
}
selectedMonth.value = monthOptions[monthOptions.length - 1]?.value || ''

const statisticsRows = ref([])

const monthIncome = ref(0)
const monthExpense = ref(0)
const monthBalance = computed(() => monthIncome.value - monthExpense.value)

const monthlyKpis = computed(() => [
  { label: '月支出', icon: ArrowDownRight, value: formatMoney(monthExpense.value), valueClass: 'text-destructive', desc: '本月累计支出' },
  { label: '月收入', icon: ArrowUpRight, value: formatMoney(monthIncome.value), valueClass: 'text-primary', desc: '本月累计收入' },
  { label: '月结余', icon: WalletCards, value: formatMoney(monthBalance.value), valueClass: monthBalance.value >= 0 ? 'text-primary' : 'text-destructive', desc: '收入减支出' }
])

const ratioType = ref('expense')

const chartTheme = computed(() => {
  mode.value
  themeColor.value
  return getChartTheme()
})

const colorPalette = computed(() => chartTheme.value.palette)

// 每日明细数据
const dailyData = computed(() => {
  const [year, month] = selectedMonth.value.split('-').map(Number)
  const daysInMonth = new Date(year, month, 0).getDate()
  const map = {}
  for (let d = 1; d <= daysInMonth; d++) {
    const key = `${selectedMonth.value}-${String(d).padStart(2, '0')}`
    map[key] = { date: key, income: 0, expense: 0, balance: 0, count: 0 }
  }
  for (const r of statisticsRows.value) {
    const key = r.transactionDate
    if (map[key]) {
      if (r.type === 'INCOME') map[key].income += Number(r.amount)
      else map[key].expense += Number(r.amount)
      map[key].count += Number(r.transactionCount)
    }
  }
  const result = Object.values(map)
  result.forEach(r => { r.balance = r.income - r.expense })
  return result
})

const filteredDailyData = computed(() => {
  if (!tableFilter.value) return dailyData.value
  return dailyData.value.filter(d => d.date.includes(tableFilter.value))
})

// 收支统计图表 (柱状图)
const incomeExpenseOption = computed(() => {
  const theme = chartTheme.value
  const labels = dailyData.value.map(d => {
    const parts = d.date.split('-')
    return parts[2] + '日'
  })
  return {
    tooltip: theme.tooltip,
    grid: { left: '3%', right: '4%', bottom: '3%', top: '10px', containLabel: true },
    xAxis: { type: 'category', data: labels, axisLabel: { ...theme.axisLabel, fontSize: 10, interval: Math.floor(labels.length / 6) }, axisTick: { show: false } },
    yAxis: { type: 'value', splitLine: theme.splitLine, axisLabel: theme.axisLabel },
    series: [
      { name: '收入', type: 'bar', barWidth: 8, itemStyle: { color: theme.primary, borderRadius: [3, 3, 0, 0] }, data: dailyData.value.map(d => d.income) },
      { name: '支出', type: 'bar', barWidth: 8, itemStyle: { color: theme.destructive, borderRadius: [3, 3, 0, 0] }, data: dailyData.value.map(d => d.expense) }
    ]
  }
})

// 资产走势 (折线图 - 累积结余)
const assetTrendOption = computed(() => {
  const theme = chartTheme.value
  const labels = dailyData.value.map(d => {
    const parts = d.date.split('-')
    return parts[2] + '日'
  })
  let cumSum = 0
  const balances = dailyData.value.map(d => { cumSum += d.balance; return cumSum })
  return {
    tooltip: theme.tooltip,
    grid: { left: '3%', right: '4%', bottom: '3%', top: '10px', containLabel: true },
    xAxis: { type: 'category', data: labels, axisLabel: { ...theme.axisLabel, fontSize: 10, interval: Math.floor(labels.length / 6) }, axisTick: { show: false } },
    yAxis: { type: 'value', splitLine: theme.splitLine, axisLabel: theme.axisLabel },
    series: [{
      name: '累积结余', type: 'line', smooth: true, symbol: 'none',
      lineStyle: { width: 2, color: theme.primary },
      areaStyle: { opacity: 0.16, color: theme.primary },
      data: balances
    }]
  }
})

// 分类占比数据（根据 ratioType 切换：支出分类 / 收入分类）
const categoryRatioData = computed(() => {
  const map = {}
  for (const r of statisticsRows.value) {
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
    color: colorPalette.value[i % colorPalette.value.length],
    percent: total > 0 ? ((value / total) * 100).toFixed(1) : '0.0'
  }))
})

// 收支占比 (分类饼图)
const ratioOption = computed(() => {
  const theme = chartTheme.value
  const data = categoryRatioData.value
  const chartData = data.length > 0
    ? data.map(d => ({ ...d, itemStyle: { color: d.color } }))
    : [{ value: 1, name: '暂无数据', itemStyle: { color: theme.border } }]
  return {
    tooltip: { ...theme.tooltip, trigger: 'item', formatter: (p) => `<strong>${p.name}</strong><br/>¥${Number(p.value).toFixed(2)} (${p.percent}%)` },
    legend: { show: data.length > 0, bottom: 0, textStyle: { fontSize: 11, color: theme.mutedForeground }, icon: 'circle', itemWidth: 8, itemHeight: 8 },
    series: [{
      type: 'pie', radius: ['45%', '68%'], center: ['50%', '38%'], padAngle: 2,
      itemStyle: { borderRadius: 4, borderColor: theme.card, borderWidth: 2 },
      label: { show: data.length > 0, formatter: '{d}%', fontSize: 11, fontWeight: 600, color: theme.foreground },
      data: chartData
    }]
  }
})

async function fetchMonthData() {
  loading.value = true
  const [year, month] = selectedMonth.value.split('-').map(Number)
  const daysInMonth = new Date(year, month, 0).getDate()
  const startStr = `${selectedMonth.value}-01`
  const endStr = `${selectedMonth.value}-${String(daysInMonth).padStart(2, '0')}`

  try {
    const listRes = await transactionStatisticsAPI({ startDate: startStr, endDate: endStr })
    if (listRes.code === 200) {
      statisticsRows.value = listRes.data || []
      let inc = 0, exp = 0
      for (const r of statisticsRows.value) {
        if (r.type === 'INCOME') inc += Number(r.amount)
        else exp += Number(r.amount)
      }
      monthIncome.value = inc
      monthExpense.value = exp
    }
  } catch (e) {
    console.error('Monthly stats fetch error:', e)
  } finally {
    loading.value = false
  }
}

function selectCurrentMonth() {
  const now = new Date()
  selectedMonth.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}
function selectPrevMonth() {
  const now = new Date()
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  selectedMonth.value = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`
}

// 选中月份变化时自动滚动到可视区域
watch(selectedMonth, () => {
  fetchMonthData()
  if (scrollRef.value) {
    const activeEl = scrollRef.value.querySelector('.month-chip-active')
    if (activeEl) {
      activeEl.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' })
    }
  }
})

function formatMoney(val) {
  return '¥' + Number(val || 0).toFixed(2)
}

onMounted(fetchMonthData)
</script>

<style scoped>
.chart-inner { height: 240px; }
</style>
