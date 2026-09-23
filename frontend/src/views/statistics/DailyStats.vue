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
        <CardDescription>总资产按现金基准、日常收支和投资当前市值统一计算</CardDescription>
      </CardHeader>

      <CardContent class="flex flex-col gap-4">
        <Alert v-if="!wealthModel.initialized">
          <WalletCards />
          <AlertTitle>总资产待初始化</AlertTitle>
          <AlertDescription class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <span>请先在财务画像填写当前日常现金余额；投资资产已单独展示。</span>
            <Button class="shrink-0" size="sm" variant="outline" @click="router.push('/profile')">填写现金余额</Button>
          </AlertDescription>
        </Alert>

        <Alert v-if="wealthError" variant="destructive">
          <AlertTitle>财富数据暂时不可用</AlertTitle>
          <AlertDescription>{{ wealthError }}</AlertDescription>
        </Alert>

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
            <p class="mt-3 text-2xl font-semibold tabular-nums" :class="asset.valueClass">{{ asset.display }}</p>
            <p class="mt-1 text-xs text-muted-foreground">{{ asset.desc }}</p>
          </div>
        </div>

        <div class="grid gap-3 lg:grid-cols-2">
          <div class="rounded-lg border bg-background">
            <div class="border-b px-4 py-3">
              <h4 class="text-sm font-medium">资产构成</h4>
              <p class="mt-0.5 text-xs text-muted-foreground">现金与投资账户分开统计，避免买入卖出重复计入</p>
            </div>
            <div class="divide-y px-4">
              <div v-for="item in wealthModel.composition" :key="item.key" class="flex items-center justify-between gap-3 py-3 text-sm">
                <span class="text-muted-foreground">{{ item.label }}</span>
                <strong class="tabular-nums">{{ item.value == null ? '待初始化' : formatMoney(item.value) }}</strong>
              </div>
            </div>
          </div>

          <div class="rounded-lg border bg-background">
            <div class="border-b px-4 py-3">
              <h4 class="text-sm font-medium">近七日资金活动</h4>
              <p class="mt-0.5 text-xs text-muted-foreground">日常账单只改变现金，不会与投资买卖重复</p>
            </div>
            <div class="divide-y px-4">
              <div v-for="item in wealthModel.activity" :key="item.key" class="flex items-center justify-between gap-3 py-3 text-sm">
                <span class="text-muted-foreground">{{ item.label }}</span>
                <strong class="tabular-nums" :class="item.key === 'expense' ? 'text-destructive' : item.key === 'income' ? 'text-primary' : ''">
                  {{ item.type === 'count' ? `${item.value} 笔` : formatMoney(item.value) }}
                </strong>
              </div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ArrowDownRight, ArrowUpRight, Banknote, Scale, TrendingUp, WalletCards } from '@lucide/vue'
import { transactionStatisticsAPI } from '../../api/transaction'
import { getWealthOverviewAPI } from '../../api/wealth'
import { Badge } from '@/components/ui/badge'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAppearance } from '@/composables/useAppearance'
import { getChartTheme } from '@/lib/chartTheme'
import { buildWealthStatistics } from '@/lib/wealthStatistics'
import { useRouter } from 'vue-router'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, TitleComponent, TooltipComponent, GridComponent, CanvasRenderer])

const sevenDayData = ref({ income: [], expense: [], labels: [] })
const transactionSummary = ref({ totalIncome: 0, totalExpense: 0, transactionCount: 0 })
const wealthOverview = ref({ initialized: false, investmentTotal: 0, investmentCash: 0, holdingMarketValue: 0 })
const wealthError = ref('')
const { mode, themeColor } = useAppearance()
const router = useRouter()

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

const wealthModel = computed(() => buildWealthStatistics(
  wealthOverview.value,
  transactionSummary.value.totalIncome,
  transactionSummary.value.totalExpense,
  transactionSummary.value.transactionCount
))

const assetCards = computed(() => {
  const icons = { totalAssets: WalletCards, dailyCash: Banknote, investmentTotal: TrendingUp, periodBalance: Scale }
  return wealthModel.value.cards.map(item => ({
    ...item,
    icon: icons[item.key],
    display: item.value == null ? '待初始化' : formatMoney(item.value),
    highlight: item.key === 'totalAssets',
    valueClass: item.key === 'periodBalance' && Number(item.value) < 0 ? 'text-destructive' : 'text-primary'
  }))
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

  const [transactionResult, wealthResult] = await Promise.allSettled([
    transactionStatisticsAPI({ startDate: fmtDate(start), endDate: fmtDate(end) }),
    getWealthOverviewAPI()
  ])

  if (transactionResult.status === 'fulfilled') {
    const res = transactionResult.value
    if (res.code === 200) {
      const records = res.data || []
      transactionSummary.value.transactionCount = records.reduce((sum, row) => sum + Number(row.transactionCount), 0)

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
      transactionSummary.value.totalIncome = totalIncome
      transactionSummary.value.totalExpense = totalExpense
    }
  } else {
    console.error('Daily stats fetch error:', transactionResult.reason)
  }

  if (wealthResult.status === 'fulfilled') {
    wealthOverview.value = wealthResult.value.data || wealthOverview.value
    wealthError.value = ''
  } else {
    wealthError.value = wealthResult.reason?.message || '请稍后重试'
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
