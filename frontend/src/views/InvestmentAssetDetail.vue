<template>
  <div class="mx-auto max-w-[1440px] space-y-5 pb-8">
    <template v-if="loading">
      <div class="flex items-center justify-between gap-4">
        <div class="flex items-center gap-3"><Skeleton class="size-8" /><div class="space-y-2"><Skeleton class="h-6 w-44" /><Skeleton class="h-4 w-64" /></div></div>
        <Skeleton class="h-8 w-32" />
      </div>
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4"><Skeleton v-for="n in 4" :key="n" class="h-24" /></div>
      <div class="grid gap-4 xl:grid-cols-12"><Skeleton class="h-[560px] xl:col-span-9" /><Skeleton class="h-[560px] xl:col-span-3" /></div>
      <div class="grid gap-4 md:grid-cols-3"><Skeleton v-for="n in 3" :key="n" class="h-40" /></div>
    </template>

    <div v-else-if="error" class="grid min-h-[440px] place-items-center">
      <div class="max-w-md text-center">
        <div class="mx-auto mb-4 grid size-12 place-items-center rounded-full bg-destructive/10 text-destructive"><TriangleAlert /></div>
        <h1 class="text-lg font-semibold">详情暂时无法加载</h1>
        <p class="mt-2 text-sm text-muted-foreground">{{ error }}</p>
        <div class="mt-5 flex justify-center gap-2"><Button variant="outline" @click="router.push('/stocks')">返回列表</Button><Button @click="loadAll">重新加载</Button></div>
      </div>
    </div>

    <template v-else-if="detail?.asset">
      <header class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
        <div class="flex min-w-0 items-start gap-3">
          <Button variant="ghost" size="icon" class="mt-0.5" title="返回资产列表" @click="router.push('/stocks')"><ArrowLeft /></Button>
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2">
              <h1 class="truncate text-2xl font-semibold tracking-tight">{{ asset.name }}</h1>
              <Badge variant="outline">{{ asset.productType === 'MUTUAL_FUND' ? '基金' : '股票' }}</Badge>
              <Badge :variant="sourceStatus.quoteStatus === 'FAILED' ? 'destructive' : 'secondary'">{{ sourceLabel }}</Badge>
            </div>
            <div class="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              <span class="font-mono text-foreground">{{ asset.code }}</span><span>{{ asset.market }}</span>
              <span>{{ sourceStatus.adjustType === 'QFQ' ? '前复权' : '单位净值' }}</span>
              <span class="inline-flex items-center gap-1"><Clock3 class="size-3.5" />{{ dataTime }}</span>
            </div>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-2 pl-11 xl:pl-0">
          <Button v-if="asset.productType !== 'MUTUAL_FUND'" variant="outline" @click="preferenceOpen = true"><SlidersHorizontal />分析周期</Button>
          <Button variant="outline" @click="editOpen = true"><Pencil />编辑持仓</Button>
          <Button :disabled="refreshing" @click="refreshAnalysis"><RefreshCw :class="refreshing && 'animate-spin'" />刷新分析</Button>
        </div>
      </header>

      <section class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Card v-for="metric in topMetrics" :key="metric.label" class="gap-2 py-4 shadow-sm">
          <CardContent class="px-3 sm:px-4">
            <div class="flex items-center gap-1 text-xs text-muted-foreground">
              <span>{{ metric.label }}</span>
              <InfoTooltip v-if="metric.help" :content="metric.help" :label="`了解${metric.label}`" />
            </div>
            <div class="mt-1 truncate text-[15px] font-semibold tabular-nums sm:text-xl" :class="metric.tone">{{ metric.value }}</div>
            <div class="mt-1 truncate text-xs text-muted-foreground">{{ metric.hint }}</div>
          </CardContent>
        </Card>
      </section>

      <section class="grid gap-4 xl:grid-cols-12">
        <Card class="gap-0 py-4 shadow-sm xl:col-span-9">
          <CardContent class="px-3 sm:px-5">
            <InvestmentKlineChart :series="detail.quoteSeries" :product-type="asset.productType" :levels="activeLevels" />
          </CardContent>
        </Card>

        <Card class="gap-0 overflow-hidden py-0 shadow-sm xl:col-span-3">
          <div v-if="asset.productType !== 'MUTUAL_FUND'" class="border-b bg-muted/30 px-4 py-3">
            <div class="flex items-center justify-between gap-2">
              <div class="flex flex-wrap items-center gap-1.5">
                <Button v-for="item in horizonOptions" :key="item.value" size="sm" class="h-7 px-2" :variant="activeHorizon === item.value ? 'secondary' : 'ghost'" @click="activeHorizon = item.value">{{ item.label }}</Button>
              </div>
              <InfoTooltip :content="helpText.analysisPeriod" label="了解分析周期" />
            </div>
          </div>
          <div class="space-y-5 p-5">
            <div>
              <div class="flex items-center justify-between gap-3">
                <Badge :variant="verdictVariant(activeVerdict)">{{ activeHeadline }}</Badge>
                <span v-if="activeAnalysis.status !== 'INSUFFICIENT'" class="inline-flex items-center gap-1 text-xs text-muted-foreground">
                  评分 {{ formatScore(activeAnalysis.score ?? technical.score) }}
                  <InfoTooltip :content="helpText.technicalScore" label="了解技术评分" />
                </span>
                <span v-else class="text-xs text-muted-foreground">该周期数据不足</span>
              </div>
              <p class="mt-3 text-2xl font-semibold" :class="verdictTone(activeVerdict)">{{ activeHeadline }}</p>
              <p v-if="conclusionReason" class="mt-1 text-sm leading-6 text-muted-foreground">{{ conclusionReason }}</p>
            </div>

            <div v-if="asset.productType === 'MUTUAL_FUND'" class="space-y-2.5">
              <ActionPriceRow label="近一月收益" :value="percent(technical.oneMonthReturn)" :tone="tone(technical.oneMonthReturn)" />
              <ActionPriceRow label="近三月收益" :value="percent(technical.threeMonthReturn)" :tone="tone(technical.threeMonthReturn)" />
              <ActionPriceRow label="近一年收益" :value="percent(technical.oneYearReturn)" :tone="tone(technical.oneYearReturn)" />
              <ActionPriceRow label="年化波动" :value="percent(technical.annualizedVolatility, false)" />
              <ActionPriceRow label="最大回撤" :value="percent(technical.maxDrawdown, false)" tone="text-destructive" />
              <ActionPriceRow label="回撤状态" :value="technical.drawdownRecovered ? '已修复' : '修复中'" />
            </div>
            <div v-else class="space-y-2.5">
              <div class="flex items-center gap-1 pb-0.5 text-xs text-muted-foreground"><span>关键价位参考</span><InfoTooltip :content="helpText.priceZones" label="了解关键价位" /></div>
              <ActionPriceRow label="买入观察区" :value="zoneText(priceZones.buy)" tone="text-emerald-600 dark:text-emerald-400" />
              <ActionPriceRow label="加仓观察区" :value="zoneText(priceZones.add)" />
              <ActionPriceRow label="持有区" :value="zoneText(priceZones.hold)" />
              <ActionPriceRow label="减仓观察区" :value="zoneText(priceZones.reduce)" tone="text-amber-600 dark:text-amber-400" />
              <ActionPriceRow label="跌破风险位" :value="riskPriceText(priceZones.risk)" tone="text-destructive" />
            </div>
          </div>
        </Card>
      </section>

      <section v-if="asset.productType !== 'MUTUAL_FUND'" class="space-y-3">
        <div class="flex items-center gap-2">
          <h2 class="font-semibold">多周期分析</h2>
          <InfoTooltip :content="helpText.multiHorizon" label="了解多周期分析" />
          <Badge v-if="cycleDifference" variant="outline" class="gap-1">周期分歧<InfoTooltip :content="helpText.cycleDifference" label="了解周期分歧" /></Badge>
        </div>
        <div class="grid gap-3 md:grid-cols-3">
          <Card v-for="item in horizonCards" :key="item.key" class="gap-3 py-4 shadow-sm">
            <CardHeader class="px-4">
              <div class="flex items-center justify-between gap-2"><CardTitle class="text-base">{{ item.label }}</CardTitle><Badge :variant="verdictVariant(item.data.verdict)">{{ verdictLabel(item.data.verdict) }}</Badge></div>
              <CardDescription>{{ horizonRange(item.data) }}</CardDescription>
            </CardHeader>
            <CardContent class="px-4">
              <p v-if="item.data.status === 'INSUFFICIENT'" class="text-sm leading-6 text-muted-foreground">{{ item.data.reason || '历史数据不足，暂不生成评分。' }}</p>
              <template v-else>
                <div class="flex items-end justify-between"><span class="text-xs text-muted-foreground">技术评分</span><strong class="text-xl tabular-nums" :class="verdictTone(item.data.verdict)">{{ formatScore(item.data.score) }}</strong></div>
                <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-muted"><div class="h-full rounded-full transition-all" :class="scoreBarTone(item.data.verdict)" :style="{ width: `${Math.max(2, Number(item.data.score || 0))}%` }" /></div>
              </template>
            </CardContent>
          </Card>
        </div>
      </section>

      <section class="grid gap-4 xl:grid-cols-12">
        <Card class="shadow-sm xl:col-span-7">
          <CardHeader>
            <div class="flex items-start justify-between gap-3">
              <div>
                <div class="flex items-center gap-1"><CardTitle>{{ asset.productType === 'MUTUAL_FUND' ? '基金表现' : '长期基本面与估值' }}</CardTitle><InfoTooltip v-if="asset.productType !== 'MUTUAL_FUND'" :content="helpText.fundamental" label="了解长期基本面与估值" /></div>
                <CardDescription>{{ asset.productType === 'MUTUAL_FUND' ? '近期表现与风险概览' : '长期价值判断不被短期技术信号修改' }}</CardDescription>
              </div>
              <Badge :variant="fundamental.status === 'READY' ? 'secondary' : 'outline'">{{ fundamentalVerdict }}</Badge>
            </div>
          </CardHeader>
          <CardContent>
            <div v-if="asset.productType === 'MUTUAL_FUND'" class="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <MetricMini label="近一月" :value="percent(technical.oneMonthReturn)" :tone="tone(technical.oneMonthReturn)" />
              <MetricMini label="近三月" :value="percent(technical.threeMonthReturn)" :tone="tone(technical.threeMonthReturn)" />
              <MetricMini label="近一年" :value="percent(technical.oneYearReturn)" :tone="tone(technical.oneYearReturn)" />
              <MetricMini label="年化波动" :value="percent(technical.annualizedVolatility, false)" />
              <MetricMini label="最大回撤" :value="percent(technical.maxDrawdown, false)" tone="text-destructive" />
              <MetricMini label="回撤修复" :value="technical.drawdownRecovered ? '已修复' : '修复中'" />
            </div>
            <div v-else-if="fundamental.status === 'READY'" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <div v-for="dimension in fundamentalDimensions" :key="dimension.key" class="rounded-lg border bg-muted/20 p-3">
                <div class="text-xs text-muted-foreground">{{ dimension.label }}</div><div class="mt-1.5 text-lg font-semibold">{{ formatScore(dimension.data.score) }}</div>
                <div class="mt-2 h-1 overflow-hidden rounded-full bg-muted"><div class="h-full rounded-full bg-primary" :style="{ width: `${Number(dimension.data.score || 0)}%` }" /></div>
              </div>
            </div>
            <div v-else class="grid min-h-32 place-items-center rounded-lg border border-dashed bg-muted/20 px-6 text-center text-sm text-muted-foreground">
              至少需要三期数据和四个有效维度才能形成长期结论。当前仍可参考技术入场时机。
            </div>
          </CardContent>
        </Card>

        <Card class="shadow-sm xl:col-span-5">
          <CardHeader>
            <div class="flex items-center gap-1"><CardTitle>数量参考</CardTitle><InfoTooltip :content="helpText.quantityReference" label="了解数量参考" /></div>
            <CardDescription>计划操作时可参考的分批金额与数量</CardDescription>
          </CardHeader>
          <CardContent class="space-y-3">
            <div v-if="quantityState.showBatches" class="grid grid-cols-3 gap-2">
              <div v-for="(batch, index) in personalized.batches || []" :key="index" class="rounded-lg border bg-muted/20 p-3 text-center">
                <div class="text-xs text-muted-foreground">第 {{ index + 1 }} 批</div>
                <div class="mt-1 font-semibold tabular-nums">{{ asset.productType === 'MUTUAL_FUND' ? money(batch.amount) : `${integer(batch.quantity)} 股` }}</div>
                <div v-if="asset.productType !== 'MUTUAL_FUND'" class="mt-0.5 text-xs text-muted-foreground">约 {{ money(batch.amount) }}</div>
              </div>
            </div>
            <div v-if="quantityState.showBudget" class="flex items-center justify-between rounded-lg border px-3 py-2.5 text-sm"><span class="text-muted-foreground">建议总预算</span><strong>{{ money(personalized.suggestedBudget) }}</strong></div>
            <div v-if="quantityState.showSell" class="flex items-center justify-between rounded-lg border px-3 py-2.5 text-sm"><span class="text-muted-foreground">减仓数量参考</span><strong>{{ asset.productType === 'MUTUAL_FUND' ? decimal(personalized.sellQuantity) + ' 份' : integer(personalized.sellQuantity) + ' 股' }}</strong></div>
            <div v-if="quantityState.emptyMessage" class="rounded-lg border border-dashed bg-muted/30 p-4">
              <div class="flex items-start gap-2.5">
                <Info class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div><p class="text-sm font-medium">当前没有可执行的数量建议</p><p class="mt-1 text-xs leading-5 text-muted-foreground">{{ quantityState.emptyMessage }}</p></div>
              </div>
            </div>
            <p class="text-xs leading-5 text-muted-foreground">数量会随账户余额和持仓变化；技术评分本身不会随财务状况变化。</p>
          </CardContent>
        </Card>
      </section>

      <section class="grid gap-4 xl:grid-cols-12">
        <Card class="shadow-sm xl:col-span-5">
          <CardHeader><CardTitle class="flex items-center gap-2"><ShieldAlert class="size-4" />财务警告</CardTitle><CardDescription>仅提醒，不影响技术分析</CardDescription></CardHeader>
          <CardContent class="space-y-2.5">
            <Alert v-for="warning in detail.financialWarnings" :key="warning.code" :variant="warning.severity === 'ERROR' ? 'destructive' : 'default'" class="py-2.5">
              <TriangleAlert v-if="warning.severity === 'WARNING'" class="text-amber-600 dark:text-amber-400" /><Info v-else />
              <AlertTitle>{{ warningTitle(warning.code) }}</AlertTitle>
              <AlertDescription>{{ warning.message }}<span v-if="warning.affectsTechnicalAnalysis === false" class="mt-1 block text-xs">不会修改技术评分、支撑压力或交易区间。</span></AlertDescription>
            </Alert>
          </CardContent>
        </Card>

        <details class="group overflow-hidden rounded-xl border bg-card shadow-sm xl:col-span-7">
          <summary class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 marker:hidden hover:bg-muted/30">
            <div><p class="font-semibold">查看详细分析</p><p class="mt-1 text-sm text-muted-foreground">历史回测与 AI 解读</p></div>
            <ChevronDown class="size-4 text-muted-foreground transition-transform group-open:rotate-180" />
          </summary>
          <div class="grid gap-4 border-t p-4 md:grid-cols-2">
            <div class="rounded-lg border bg-muted/10 p-4">
              <div class="flex items-center gap-1"><h3 class="font-semibold">历史回测</h3><InfoTooltip :content="helpText.backtest" label="了解历史回测" /></div>
              <p class="mt-1 text-xs leading-5 text-muted-foreground">只用当时可见数据，不使用未来信息生成信号</p>
              <div class="mt-4 space-y-3">
                <MetricLine label="历史出现" :value="`${activeBacktest.occurrences || 0} 次`" />
                <MetricLine label="胜率" :value="percent(activeBacktest.winRate, false)" />
                <MetricLine label="中位前瞻收益" :value="percent(activeBacktest.medianForwardReturn)" :tone="tone(activeBacktest.medianForwardReturn)" />
                <MetricLine label="样本最大回撤" :value="percent(activeBacktest.maxDrawdown, false)" tone="text-destructive" />
              </div>
            </div>
            <div class="rounded-lg border bg-muted/10 p-4">
              <h3 class="flex items-center gap-2 font-semibold"><Sparkles class="size-4 text-primary" />AI 解读</h3>
              <p class="mt-1 text-xs leading-5 text-muted-foreground">AI 只解释规则结果，不能覆盖评分与价位</p>
              <div class="mt-4 rounded-lg border bg-background p-4 text-sm leading-7 text-foreground/90 whitespace-pre-line">{{ ai.text || '正在根据最新分析生成中文解读…' }}</div>
              <div class="mt-3 flex items-center justify-between text-xs text-muted-foreground"><span>{{ ai.status === 'READY' ? '缓存解读' : '后台生成中' }}</span><span>最短刷新间隔 30 分钟</span></div>
            </div>
          </div>
        </details>
      </section>

      <InvestmentAssetDrawer v-model:open="editOpen" :asset="asset" @saved="loadAll" />

      <HorizonProfileDialog
        v-model:open="preferenceOpen"
        :profile="horizonProfile"
        :saving="savingPreference"
        scope="asset"
        @save="savePreference"
        @clear="clearPreference"
      />

    </template>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ChevronDown, Clock3, Info, Pencil, RefreshCw, ShieldAlert,
  SlidersHorizontal, Sparkles, TriangleAlert
} from '@lucide/vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { InfoTooltip } from '@/components/ui/tooltip'
import InvestmentAssetDrawer from '@/components/investment/InvestmentAssetDrawer.vue'
import HorizonProfileDialog from '@/components/investment/HorizonProfileDialog.vue'
import InvestmentKlineChart from '@/components/investment/InvestmentKlineChart.vue'
import { buildQuantityReferenceState, missingPriceZoneText } from '@/lib/investmentActionState'
import { investmentHelpText as helpText } from '@/lib/investmentHelpText'
import { feedback } from '@/lib/feedback'
import {
  clearInvestmentAssetHorizonOverrideAPI,
  getInvestmentAssetDetailAPI,
  refreshInvestmentAssetAnalysisAPI,
  updateInvestmentAssetHorizonOverrideAPI,
} from '@/api/investment'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const refreshing = ref(false)
const savingPreference = ref(false)
const error = ref('')
const detail = ref(null)
const activeHorizon = ref('')
const editOpen = ref(false)
const preferenceOpen = ref(false)

const asset = computed(() => detail.value?.asset || {})
const technical = computed(() => detail.value?.technicalAnalysis || {})
const fundamental = computed(() => detail.value?.fundamentalAnalysis || {})
const personalized = computed(() => detail.value?.personalizedAction || {})
const backtest = computed(() => detail.value?.backtestSummary || {})
const ai = computed(() => detail.value?.aiExplanation || {})
const sourceStatus = computed(() => detail.value?.sourceStatus || {})
const horizonProfile = computed(() => detail.value?.analysisPreference || { settings: [] })
const horizonOptions = computed(() => (horizonProfile.value.settings || []).map(item => ({
  value: item.code,
  label: item.displayName,
  primary: Boolean(item.primary),
})))
const isFund = computed(() => asset.value.productType === 'MUTUAL_FUND')
const activeAnalysis = computed(() => technical.value.horizons?.[activeHorizon.value] || technical.value)
const activeBacktest = computed(() => backtest.value.horizons?.[activeHorizon.value] || backtest.value)
const activeLevels = computed(() => activeAnalysis.value.levels || technical.value.levels || {})
const priceZones = computed(() => isFund.value
  ? personalized.value.priceZones || technical.value.actionZones || {}
  : activeAnalysis.value.actionZones || technical.value.actionZones || personalized.value.priceZones || {})
const activeVerdict = computed(() => isFund.value
  ? actionVerdict(technical.value.action)
  : activeAnalysis.value.verdict || technical.value.verdict || actionVerdict(technical.value.action))
const activeHeadline = computed(() => activeAnalysis.value.status === 'INSUFFICIENT'
  ? '数据不足'
  : isFund.value ? fundActionLabel(technical.value.action) : verdictLabel(activeVerdict.value))
const activePeriodText = computed(() => horizonRange(activeAnalysis.value))
const cycleDifference = computed(() => new Set(Object.values(technical.value.horizons || {}).map(item => item.verdict).filter(Boolean)).size > 1)
const conclusionReason = computed(() => {
  if (isFund.value) return ''
  if (activeAnalysis.value.status === 'INSUFFICIENT') {
    return activeAnalysis.value.reason || '该周期所需历史数据不足，暂不生成评分。'
  }
  const reason = activeVerdict.value === 'FAVORABLE'
    ? '当前趋势和动量相对较强。'
    : activeVerdict.value === 'WEAK'
      ? '当前趋势与动量整体偏弱。'
      : '当前指标还没有形成一致方向。'
  return `${reason}${activePeriodText.value}，${cycleDifference.value ? '不同周期的判断存在分歧。' : '不同周期的方向较一致。'}`
})
const horizonCards = computed(() => horizonOptions.value.map(item => ({ key: item.value, label: item.label, data: technical.value.horizons?.[item.value] || {} })))
const quantityState = computed(() => buildQuantityReferenceState({
  productType: asset.value.productType,
  suggestedBudget: personalized.value.suggestedBudget,
  sellQuantity: personalized.value.sellQuantity,
  technicalConfidence: personalized.value.technicalConfidence,
  batches: personalized.value.batches || [],
}))
const fundamentalDimensions = computed(() => {
  const labels = { growth: '成长', profitability: '盈利', cashQuality: '现金质量', resilience: '财务韧性', valuation: '估值' }
  return Object.entries(fundamental.value.dimensions || {}).map(([key, data]) => ({ key, label: labels[key] || key, data }))
})
const fundamentalVerdict = computed(() => {
  if (asset.value.productType === 'MUTUAL_FUND') return fundActionLabel(technical.value.action)
  return ({ ATTRACTIVE: '长期较有吸引力', FAIR: '长期中性', CAUTIOUS: '长期需谨慎', INSUFFICIENT: '数据不足' }[fundamental.value.verdict] || '数据不足')
})
const sourceLabel = computed(() => ({ READY: '数据正常', CACHED: '缓存数据', FAILED: '数据失败', INSUFFICIENT: '数据不足' }[sourceStatus.value.quoteStatus] || '数据状态未知'))
const dataTime = computed(() => sourceStatus.value.quoteDate || asset.value.dataDate || '暂无日期')
const topMetrics = computed(() => [
  { label: asset.value.productType === 'MUTUAL_FUND' ? '最新净值' : '当前价格', value: originalMoney(asset.value.latestPrice, asset.value.currency), hint: `${signedPercent(asset.value.changePercent)} 今日涨跌`, tone: tone(asset.value.changePercent) },
  { label: '持仓市值', value: money(asset.value.marketValueCny), hint: asset.value.quantity == null ? '尚未填写持仓' : `${decimal(asset.value.quantity)} ${asset.value.productType === 'MUTUAL_FUND' ? '份' : '股'}` },
  { label: '持仓盈亏', value: signedMoney(asset.value.unrealizedPnlCny), hint: `${signedPercent(asset.value.holdingReturnPercent)} 持仓收益`, tone: tone(asset.value.unrealizedPnlCny) },
  { label: isFund.value ? '当前综合评分' : '当前技术评分', value: formatScore(isFund.value ? technical.value.score : activeAnalysis.value.score ?? technical.value.score), hint: activeHeadline.value, tone: verdictTone(activeVerdict.value), help: isFund.value ? helpText.fundScore : helpText.technicalScore }
])

const ActionPriceRow = defineComponent({ props: { label: String, value: String, tone: String }, setup: props => () => h('div', { class: 'flex items-center justify-between gap-3 border-b border-border/60 pb-2 text-sm last:border-0 last:pb-0' }, [h('span', { class: 'text-muted-foreground' }, props.label), h('strong', { class: ['tabular-nums text-right', props.tone] }, props.value || '-')]) })
const MetricMini = defineComponent({ props: { label: String, value: String, tone: String }, setup: props => () => h('div', { class: 'rounded-lg border bg-muted/20 p-3' }, [h('div', { class: 'text-xs text-muted-foreground' }, props.label), h('div', { class: ['mt-1.5 font-semibold tabular-nums', props.tone] }, props.value)]) })
const MetricLine = defineComponent({ props: { label: String, value: String, tone: String }, setup: props => () => h('div', { class: 'flex items-center justify-between border-b border-border/60 pb-2 text-sm last:border-0' }, [h('span', { class: 'text-muted-foreground' }, props.label), h('strong', { class: ['tabular-nums', props.tone] }, props.value)]) })

watch(() => route.params.assetId, loadAll, { immediate: true })
watch(horizonOptions, options => {
  if (options.some(item => item.value === activeHorizon.value)) return
  activeHorizon.value = options.find(item => item.primary)?.value || options[0]?.value || ''
}, { immediate: true })

async function loadAll() {
  loading.value = true
  error.value = ''
  const id = route.params.assetId
  try {
    const response = await getInvestmentAssetDetailAPI(id)
    detail.value = response.data
  } catch (requestError) {
    error.value = requestError?.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

async function refreshAnalysis() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    const response = await refreshInvestmentAssetAnalysisAPI(route.params.assetId)
    detail.value = response.data
    feedback.success('分析已刷新')
  } finally { refreshing.value = false }
}

async function savePreference(payload) {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    const response = await updateInvestmentAssetHorizonOverrideAPI(route.params.assetId, payload)
    detail.value = response.data
    preferenceOpen.value = false
    feedback.success('该资产的周期设置已保存')
  } finally { savingPreference.value = false }
}

async function clearPreference() {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    const response = await clearInvestmentAssetHorizonOverrideAPI(route.params.assetId)
    detail.value = response.data
    preferenceOpen.value = false
    feedback.success('已恢复全局周期设置')
  } finally { savingPreference.value = false }
}

function verdictLabel(value) { return ({ FAVORABLE: '值得关注', WAIT: '中性观察', WEAK: '技术偏弱' }[value] || '等待数据') }
function verdictVariant(value) { return value === 'WEAK' ? 'destructive' : value === 'FAVORABLE' ? 'secondary' : 'outline' }
function verdictTone(value) { return value === 'WEAK' ? 'text-destructive' : value === 'FAVORABLE' ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400' }
function scoreBarTone(value) { return value === 'WEAK' ? 'bg-destructive' : value === 'FAVORABLE' ? 'bg-emerald-500' : 'bg-amber-500' }
function actionVerdict(value) { return ({ ACCUMULATE: 'FAVORABLE', HOLD: 'WAIT', PAUSE: 'WEAK', TAKE_PROFIT: 'WAIT' }[value] || 'WAIT') }
function fundActionLabel(value) { return ({ ACCUMULATE: '定投参考', HOLD: '继续持有', PAUSE: '暂停追加', TAKE_PROFIT: '分批止盈' }[value] || '等待数据') }
function horizonRange(value) { return value?.minimumDays != null ? `${value.minimumDays}–${value.maximumDays} 个交易日` : '等待足够历史数据' }
function missingZoneReason() { return missingPriceZoneText({ hasLatestPrice: asset.value.latestPrice != null, historyCount: detail.value?.quoteSeries?.length || 0 }) }
function zoneText(zone) { if (!zone) return missingZoneReason(); const low = zone.low ?? zone.price; const high = zone.high ?? zone.price; return low == null ? missingZoneReason() : low === high ? singlePrice(low) : `${singlePrice(low)} – ${singlePrice(high)}` }
function riskPriceText(zone) { return zone?.price == null ? missingZoneReason() : singlePrice(zone.price) }
function singlePrice(value) { return value == null ? '暂无数据' : new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(Number(value)) }
function formatScore(value) { return value == null ? '-' : Number(value).toFixed(1) }
function money(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 2 }).format(Number(value)) }
function originalMoney(value, currency = 'CNY') { if (value == null) return '-'; try { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency, maximumFractionDigits: 4 }).format(Number(value)) } catch { return `${value} ${currency}` } }
function signedMoney(value) { if (value == null) return '-'; const n = Number(value); return `${n > 0 ? '+' : ''}${money(n)}` }
function signedPercent(value) { return value == null ? '-' : `${Number(value) > 0 ? '+' : ''}${Number(value).toFixed(2)}%` }
function percent(value, signed = true) { if (value == null) return '-'; const n = Number(value); return `${signed && n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function decimal(value) { return value == null ? '-' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(Number(value)) }
function integer(value) { return value == null ? '0' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(Number(value)) }
function tone(value) { const n = Number(value || 0); return n > 0 ? 'text-emerald-600 dark:text-emerald-400' : n < 0 ? 'text-destructive' : '' }
function warningTitle(code) { return ({ WEALTH_NOT_INITIALIZED: '现金基准未初始化', RESERVE_LOW: '备用金不足', CONCENTRATION_HIGH: '持仓集中度较高', SAVINGS_GOAL: '储蓄目标提醒', RISK_PREFERENCE: '风险偏好提醒', REFERENCE_ONLY: '分析边界' }[code] || '财务提醒') }
</script>
