<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { quant } from '@/api/quantWorkbench'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import QuantPageHeader from './QuantPageHeader.vue'
import { useOperation } from './shared'

const { busy, error, load } = useOperation()
const overview = ref([])
const result = ref({ items: [], total: 0, page: 1, size: 25 })
const jobs = ref([])
const search = ref('')
const market = ref('')
const marketGroup = ref('')
const assetType = ref('')
const status = ref('')
const page = ref(1)
const typeNames = { STOCK: '股票', ETF: 'ETF', FUND: '基金', MUTUAL_FUND: '基金', INDEX: '指数' }
const categories = [
  ['A股', group => group.productType === 'STOCK' && ['SSE', 'SZSE', 'BSE'].includes(group.market), 'STOCK', ''],
  ['美股', group => group.productType === 'STOCK' && ['NYSE', 'NASDAQ'].includes(group.market), 'STOCK', ''],
  ['ETF', group => group.productType === 'ETF', 'ETF', ''],
  ['基金', group => ['FUND', 'MUTUAL_FUND'].includes(group.productType), 'FUND', ''],
  ['指数', group => group.productType === 'INDEX', 'INDEX', ''],
]
const cards = computed(() => categories.map(([name, match, type]) => {
  const groups = overview.value.filter(match)
  const dates = groups.map(group => group.historyStartDate).filter(Boolean).sort()
  const latest = groups.map(group => group.latestDataDate).filter(Boolean).sort()
  return { name, type, count: groups.reduce((sum, group) => sum + Number(group.productCount || 0), 0),
    start: dates[0] || '—', end: latest.at(-1) || '—',
    today: groups.reduce((sum, group) => sum + Number(group.todaySynced || 0), 0),
    quality: groups.some(group => group.dataQualityStatus !== 'NOT_EVALUATED') ? '已评估' : '待评估',
    sync: groups.some(group => ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(group.syncTaskStatus)) ? '同步中' :
      groups.some(group => group.syncTaskStatus === 'FAILED') ? '部分失败' : '空闲' }
}))
const markets = computed(() => [...new Set(overview.value.map(group => group.market))].sort())
const totalPages = computed(() => Math.max(1, Math.ceil(Number(result.value.total || 0) / result.value.size)))

async function refresh() {
  const [summary, products, recentJobs] = await Promise.all([
    quant.marketData.overview(),
    quant.marketData.products({ search: search.value || undefined, market: market.value || undefined,
      marketGroup: marketGroup.value || undefined,
      assetType: assetType.value || undefined, status: status.value || undefined, page: page.value, size: 25 }),
    quant.marketData.jobs(),
  ])
  overview.value = summary
  result.value = products
  jobs.value = recentJobs
}
let debounce
watch([search, market, marketGroup, assetType, status], () => {
  page.value = 1
  clearTimeout(debounce)
  debounce = setTimeout(() => load(refresh), 250)
})
watch(page, () => load(refresh))
function choose(card) {
  assetType.value = card.type
  market.value = ''
  marketGroup.value = card.name === '美股' ? 'US' : card.name === 'A股' ? 'CN_A' : ''
}
onMounted(() => load(refresh))
</script>

<template>
  <QuantPageHeader title="数据中心" description="全市场研究数据，与我的投资资产分开。">
    <template #actions><Button variant="outline" :disabled="busy" @click="load(refresh)">刷新</Button></template>
  </QuantPageHeader>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-5 mb-6">
    <button v-for="card in cards" :key="card.name" class="panel text-left p-4 hover:border-primary" @click="choose(card)">
      <strong>{{ card.name }}</strong><div class="text-2xl font-semibold mt-2">{{ card.count }}</div>
      <p class="muted text-xs mt-2">{{ card.start }} ～ {{ card.end }}</p>
      <p class="muted text-xs">今日同步 {{ card.today }} · {{ card.quality }}</p>
      <p class="muted text-xs">任务 {{ card.sync }}</p>
    </button>
  </div>
  <div class="panel">
    <div class="toolbar flex flex-wrap gap-2">
      <Input v-model="search" placeholder="搜索代码或名称" aria-label="搜索代码或名称" class="max-w-[240px]" />
      <select v-model="market" aria-label="市场" @change="marketGroup='' "><option value="">全部市场</option><option v-for="item in markets" :key="item" :value="item">{{ item }}</option></select>
      <select v-model="assetType" aria-label="类型"><option value="">全部类型</option><option value="STOCK">股票</option><option value="ETF">ETF</option><option value="FUND">基金</option><option value="INDEX">指数</option></select>
      <select v-model="status" aria-label="状态"><option value="">全部状态</option><option value="ACTIVE">在市</option><option value="DELISTED">退市</option></select>
    </div>
    <div class="table-wrap"><Table><TableHeader><TableRow><TableHead>代码</TableHead><TableHead>名称</TableHead><TableHead>市场</TableHead><TableHead>类型</TableHead><TableHead>历史起点</TableHead><TableHead>最新日期</TableHead><TableHead>观察数</TableHead><TableHead>数据状态</TableHead></TableRow></TableHeader><TableBody>
      <TableRow v-for="product in result.items" :key="product.id"><TableCell><RouterLink :to="`/quant/data/${product.id}`" class="text-primary hover:underline">{{ product.code }}</RouterLink></TableCell><TableCell>{{ product.name }}</TableCell><TableCell>{{ product.exchangeCode || product.market }}</TableCell><TableCell>{{ typeNames[product.productType] || product.productType }}</TableCell><TableCell>{{ product.historyStartDate || '—' }}</TableCell><TableCell>{{ product.historyEndDate || '—' }}</TableCell><TableCell>{{ product.observations }}</TableCell><TableCell>{{ product.dataStatus === 'AVAILABLE' ? '已同步' : '待同步' }}</TableCell></TableRow>
    </TableBody></Table><p v-if="!result.items.length" class="muted py-5 text-center">暂无符合条件的证券</p></div>
    <div class="flex items-center justify-between mt-4"><span class="muted text-xs">共 {{ result.total }} 个 · 第 {{ page }} / {{ totalPages }} 页</span><div class="flex gap-2"><Button variant="outline" size="sm" :disabled="page <= 1" @click="page--">上一页</Button><Button variant="outline" size="sm" :disabled="page >= totalPages" @click="page++">下一页</Button></div></div>
  </div>
  <details class="panel mt-5"><summary>最近同步任务（{{ jobs.length }}）</summary><div class="table-wrap mt-3"><Table><TableHeader><TableRow><TableHead>代码</TableHead><TableHead>市场</TableHead><TableHead>任务</TableHead><TableHead>状态</TableHead><TableHead>断点</TableHead><TableHead>目标日期</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="job in jobs" :key="job.id"><TableCell>{{ job.code }}</TableCell><TableCell>{{ job.market }}</TableCell><TableCell>{{ job.jobType }}</TableCell><TableCell>{{ job.status }}</TableCell><TableCell>{{ job.checkpointDate || '—' }}</TableCell><TableCell>{{ job.targetDate }}</TableCell></TableRow></TableBody></Table></div></details>
</template>
