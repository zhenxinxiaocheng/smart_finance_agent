<template>
  <div class="daily-stats flex flex-col gap-[var(--app-section-gap)]">
    <!-- ===== 近七日统计 ===== -->
    <Card>
      <CardHeader class="flex flex-row items-center justify-between">
        <div>
          <CardTitle>近七日统计</CardTitle>
          <CardDescription>按天查看最近一周收入、支出与结余走势</CardDescription>
        </div>
        <Badge variant="outline">{{ sevenDayRange }}</Badge>
      </CardHeader>

      <!-- 统计卡片 -->
      <CardContent class="flex flex-col gap-4">
        <div class="grid gap-3 md:grid-cols-3">
          <div class="rounded-lg border bg-card p-[var(--app-card-padding)]" v-for="card in dailyCards" :key="card.label">
            <div class="flex items-center gap-3">
              <div class="flex size-10 items-center justify-center rounded-lg border bg-background text-foreground">
                <component :is="card.icon" data-icon="inline-start" />
              </div>
              <div class="min-w-0">
                <p class="text-sm text-muted-foreground">{{ card.label }}</p>
                <p class="text-xl font-semibold tabular-nums" :class="card.valueClass">{{ card.formatted }}</p>
              </div>
            </div>
          </div>
        </div>

        <!-- 七日收支趋势图 -->
        <div class="rounded-lg border bg-card">
          <div class="border-b px-4 py-3">
            <h4 class="text-sm font-medium">七日收支趋势</h4>
          </div>
          <div class="p-3">
            <v-chart :option="sevenDayChartOption" class="chart-bar" autoresize />
          </div>
        </div>
      </CardContent>
    </Card>

    <!-- ===== 资产汇总 ===== -->
    <Card>
      <CardHeader>
        <CardTitle>资产汇总</CardTitle>
        <CardDescription>汇总当前周期的收入、支出、净资产和交易笔数</CardDescription>
      </CardHeader>

      <CardContent>
        <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <div
            v-for="asset in assetCards"
            :key="asset.label"
            class="rounded-lg border bg-background p-4"
            :class="asset.highlight ? 'border-primary/20 bg-primary/5' : ''"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm text-muted-foreground">{{ asset.label }}</span>
              <component :is="asset.icon" data-icon="inline-start" class="text-muted-foreground" />
            </div>
            <p class="mt-3 text-2xl font-semibold tabular-nums" :class="asset.valueClass">{{ asset.value }}</p>
            <p class="mt-1 text-xs text-muted-foreground">{{ asset.desc }}</p>
          </div>
        </div>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ArrowDownRight, ArrowUpRight, ReceiptText, WalletCards } from '@lucide/vue'
import { listTransactionsAPI } from '../../api/transaction'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAppearance } from '@/composables/useAppearance'
import { getChartTheme } from '@/lib/chartTheme'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, TitleComponent, TooltipComponent, GridComponent, CanvasRenderer])

const sevenDayData = ref({ income: [], expense: [], labels: [] })
const assetSummary = ref({ totalAssets: 0, totalIncome: 0, totalExpense: 0, transactionCount: 0 })
const { mode, themeColor } = useAppearance()

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
    { label: '收入', icon: ArrowUpRight, valueClass: 'text-primary', formatted: formatMoney(income7) },
    { label: '支出', icon: ArrowDownRight, valueClass: 'text-destructive', formatted: formatMoney(expense7) },
    { label: '结余', icon: WalletCards, valueClass: balance >= 0 ? 'text-primary' : 'text-destructive', formatted: formatMoney(balance) }
  ]
})

const assetCards = computed(() => {
  return [
    { label: '总资产', icon: WalletCards, value: formatMoney(assetSummary.value.totalAssets), desc: '当前累计净资产', valueClass: 'text-primary', highlight: true },
    { label: '总收入', icon: ArrowUpRight, value: formatMoney(assetSummary.value.totalIncome), desc: '期间累计收入', valueClass: 'text-primary' },
    { label: '总支出', icon: ArrowDownRight, value: formatMoney(assetSummary.value.totalExpense), desc: '期间累计支出', valueClass: 'text-destructive' },
    { label: '交易笔数', icon: ReceiptText, value: assetSummary.value.transactionCount, desc: '期间交易总笔数', valueClass: 'text-foreground' }
  ]
})

const sevenDayChartOption = computed(() => {
  mode.value
  themeColor.value
  const theme = getChartTheme()
  return {
    tooltip: theme.tooltip,
    grid: { left: '3%', right: '4%', bottom: '3%', top: '20px', containLabel: true },
    xAxis: {
      type: 'category',
      data: sevenDayData.value.labels,
      axisLine: theme.axisLine,
      axisTick: { show: false },
      axisLabel: theme.axisLabel
    },
    yAxis: {
      type: 'value',
      splitLine: theme.splitLine,
      axisLabel: theme.axisLabel
    },
    series: [
      {
        name: '收入', type: 'bar', barWidth: 14,
        itemStyle: { color: theme.primary, borderRadius: [4, 4, 0, 0] },
        data: sevenDayData.value.income
      },
      {
        name: '支出', type: 'bar', barWidth: 14,
        itemStyle: { color: theme.destructive, borderRadius: [4, 4, 0, 0] },
        data: sevenDayData.value.expense
      }
    ]
  }
})

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
.chart-bar { height: 280px; }
</style>
