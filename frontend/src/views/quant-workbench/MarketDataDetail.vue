<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { quant } from '@/api/quantWorkbench'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import QuantPageHeader from './QuantPageHeader.vue'
import { useOperation } from './shared'

const route = useRoute()
const { busy, error, load } = useOperation()
const product = ref(null)
const daily = ref([])
const typeNames = { STOCK: '股票', ETF: 'ETF', FUND: '基金', MUTUAL_FUND: '基金', INDEX: '指数' }
const statusNames = { ACTIVE: '在市', DELISTED: '退市' }
const ordered = computed(() => [...daily.value].reverse())
const closePoints = computed(() => ordered.value.filter(row => row.closePrice != null))
const pricePath = computed(() => {
  const points = closePoints.value
  if (!points.length) return ''
  const values = points.map(row => Number(row.closePrice))
  const min = Math.min(...values), max = Math.max(...values), span = max - min || 1
  return values.map((value, index) => `${index ? 'L' : 'M'}${(index / Math.max(1, values.length - 1) * 1000).toFixed(2)},${(175 - (value - min) / span * 150).toFixed(2)}`).join(' ')
})
const volumeBars = computed(() => {
  const points = ordered.value
  const max = Math.max(1, ...points.map(row => Number(row.volume || 0)))
  return points.map((row, index) => ({ x: index / Math.max(1, points.length) * 1000,
    width: Math.max(1, 1000 / Math.max(1, points.length) - 2),
    height: Number(row.volume || 0) / max * 50 }))
})
async function refresh() {
  const id = route.params.productId
  const [detail, preview] = await Promise.all([quant.marketData.detail(id), quant.marketData.daily(id, 180)])
  product.value = detail
  daily.value = preview
}
onMounted(() => load(refresh))
watch(() => route.params.productId, () => load(refresh))
</script>

<template>
  <QuantPageHeader :title="product ? `${product.name} · ${product.code}` : '市场数据详情'" description="日频研究数据">
    <template #back><RouterLink to="/quant/data" class="text-sm text-primary hover:underline">← 返回数据中心</RouterLink></template>
    <template #actions><Button variant="outline" :disabled="busy" @click="load(refresh)">刷新</Button></template>
  </QuantPageHeader>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <template v-if="product">
    <div class="grid gap-4 md:grid-cols-2 mb-5">
      <section class="panel"><h3>基础信息</h3><dl class="quant-detail-meta mt-3"><div><dt>市场 / 交易所</dt><dd>{{ product.market }} / {{ product.exchangeCode || '—' }}</dd></div><div><dt>类型</dt><dd>{{ typeNames[product.productType] || product.productType }}</dd></div><div><dt>币种</dt><dd>{{ product.currency }}</dd></div><div><dt>状态</dt><dd>{{ statusNames[product.status] || product.status }}</dd></div><div><dt>上市日期</dt><dd>{{ product.listingDate || '未知' }}</dd></div><div><dt>来源</dt><dd>{{ product.sourceMetadata || '未知' }}</dd></div></dl></section>
      <section class="panel"><h3>数据覆盖与质量</h3><dl class="quant-detail-meta mt-3"><div><dt>历史区间</dt><dd>{{ product.coverage.historyStartDate || '—' }} ～ {{ product.coverage.historyEndDate || '—' }}</dd></div><div><dt>观察数</dt><dd>{{ product.coverage.observations }}</dd></div><div><dt>缺失交易日</dt><dd>{{ product.coverage.missingTradingDays ?? '未能核实' }}</dd></div><div><dt>覆盖率</dt><dd>{{ product.coverage.coverageRatio == null ? '未能核实' : `${(product.coverage.coverageRatio * 100).toFixed(1)}%` }}</dd></div><div><dt>质量状态</dt><dd>{{ product.quality?.qualityStatus || '未评估' }}</dd></div><div><dt>复权口径</dt><dd>{{ product.coverage.adjustType }}</dd></div><div><dt>数据源</dt><dd>{{ product.coverage.source || '—' }}</dd></div><div><dt>适配版本</dt><dd>{{ product.coverage.adapterVersion || '—' }}</dd></div><div><dt>最后同步</dt><dd>{{ product.coverage.lastSyncedAt || '—' }}</dd></div></dl></section>
    </div>
    <section class="panel"><h3>日线预览</h3><p class="muted text-xs mt-1">价格 / 净值与成交量；成交量缺失时不补造。</p>
      <svg v-if="pricePath" class="w-full h-[250px] mt-4" viewBox="0 0 1000 250" preserveAspectRatio="none" role="img" aria-label="价格和成交量走势"><path :d="pricePath" fill="none" stroke="currentColor" stroke-width="3" class="text-primary" /><rect v-for="(bar, index) in volumeBars" :key="index" :x="bar.x" :y="248-bar.height" :width="bar.width" :height="bar.height" fill="currentColor" class="text-muted-foreground/50" /></svg>
      <p v-else class="muted py-5">暂无日线数据</p>
      <div v-if="daily.length" class="table-wrap mt-4 max-h-[360px] overflow-y-auto"><Table><TableHeader><TableRow><TableHead>日期</TableHead><TableHead>价格 / 净值</TableHead><TableHead>成交量</TableHead><TableHead>来源</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="row in daily" :key="row.tradeDate"><TableCell>{{ row.tradeDate }}</TableCell><TableCell>{{ row.closePrice }}</TableCell><TableCell>{{ row.volume ?? '—' }}</TableCell><TableCell>{{ row.source }}</TableCell></TableRow></TableBody></Table></div>
    </section>
  </template>
</template>
