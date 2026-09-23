<template>
  <div class="space-y-3">
    <div v-if="productType !== 'MUTUAL_FUND'" class="rounded-lg border bg-muted/20 p-2.5">
      <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div class="flex flex-wrap items-center gap-1">
          <span class="mr-1 text-[11px] font-medium text-muted-foreground">均线</span>
          <Button
            v-for="item in maPeriods"
            :key="item.value"
            size="sm"
            variant="ghost"
            class="h-6 gap-1.5 rounded-md px-1.5 text-[11px]"
            :class="visibleMAs.includes(item.value) ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground'"
            @click="toggleMA(item.value)"
          >
            <span class="size-1.5 rounded-full" :style="{ backgroundColor: item.color }" />
            {{ item.label }}
          </Button>
        </div>
        <div class="flex flex-wrap items-center gap-1">
          <span class="mr-1 text-[11px] font-medium text-muted-foreground">副图</span>
          <Button
            v-for="item in indicators"
            :key="item.value"
            size="sm"
            variant="ghost"
            class="h-6 rounded-md px-2 text-[11px]"
            :class="activeIndicator === item.value ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground'"
            @click="activeIndicator = item.value"
          >
            {{ item.label }}
          </Button>
        </div>
      </div>
    </div>

    <div
      v-if="historyWarning"
      class="flex items-start gap-2 rounded-md border border-amber-500/20 bg-amber-500/5 px-3 py-2 text-xs text-amber-700 dark:text-amber-300"
    >
      <Info class="mt-0.5 size-3.5 shrink-0" />
      <span>{{ historyWarning }}</span>
    </div>

    <div v-if="series.length" class="investment-chart h-[360px] w-full sm:h-[420px] md:h-[500px] xl:h-[520px]">
      <VChart ref="chartRef" :option="option" autoresize class="size-full" @dblclick="restore" />
    </div>
    <div v-else class="grid h-[320px] place-items-center rounded-lg border border-dashed bg-muted/20 text-sm text-muted-foreground">
      暂无足够行情数据
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  ToolboxComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Info } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { useAppearance } from '@/composables/useAppearance'
import { getChartTheme } from '@/lib/chartTheme'
import { availableMovingAveragePeriods, buildInvestmentChartOption } from '@/lib/investmentChart'

use([
  BarChart, CandlestickChart, LineChart, AxisPointerComponent, DataZoomComponent,
  GridComponent, LegendComponent, MarkAreaComponent, MarkLineComponent,
  ToolboxComponent, TooltipComponent, CanvasRenderer
])

const props = defineProps({
  series: { type: Array, default: () => [] },
  productType: { type: String, default: 'STOCK' },
  levels: { type: Object, default: () => ({}) },
  historyWarning: { type: String, default: '' },
  indicatorConfig: { type: Object, default: () => ({}) },
})

const indicators = [
  { value: 'MACD', label: 'MACD' },
  { value: 'RSI', label: 'RSI' },
  { value: 'KDJ', label: 'KDJ' },
  { value: 'BOLL', label: 'BOLL' },
  { value: 'ATR', label: 'ATR' },
  { value: 'NONE', label: '关闭' }
]
const maColors = ['var(--primary)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)', 'var(--chart-5)', 'var(--destructive)']
const maPeriods = computed(() => availableMovingAveragePeriods(props.series).map((period, index) => ({
  value: period,
  label: `MA${period}`,
  color: maColors[index % maColors.length],
})))
const activeIndicator = ref('MACD')
const visibleMAs = ref([])
const chartRef = ref(null)
const { mode, themeColor } = useAppearance()

watch(maPeriods, periods => {
  const available = periods.map(item => item.value)
  const retained = visibleMAs.value.filter(item => available.includes(item))
  visibleMAs.value = retained.length ? retained : available
}, { immediate: true })

const option = computed(() => {
  mode.value
  themeColor.value
  return buildInvestmentChartOption({
    series: props.series,
    productType: props.productType,
    indicator: props.productType === 'MUTUAL_FUND' ? 'NONE' : activeIndicator.value,
    theme: getChartTheme(),
    levels: props.levels,
    visibleMAs: visibleMAs.value,
    indicatorConfig: props.indicatorConfig,
  })
})

function toggleMA(period) {
  visibleMAs.value = visibleMAs.value.includes(period)
    ? visibleMAs.value.filter(item => item !== period)
    : [...visibleMAs.value, period].sort((a, b) => a - b)
}

function restore() {
  chartRef.value?.dispatchAction?.({ type: 'restore' })
}
</script>

<style scoped>
.investment-chart :deep(canvas) {
  cursor: crosshair;
}
</style>
