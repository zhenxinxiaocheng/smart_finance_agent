<script setup>
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import MetricGrid from './MetricGrid.vue'
import DataTable from './DataTable.vue'
import OrderTable from './OrderTable.vue'
import { label, format, metricNames } from './shared'
import { buyAndHoldComparison } from './buyAndHold'
import { getChartTheme } from '@/lib/chartTheme'
import { useAppearance } from '@/composables/useAppearance'
const { mode, themeColor } = useAppearance()
use([CanvasRenderer, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent])
const props = defineProps({ result: { type: Object, default: () => ({}) }, hideQualificationStatus: Boolean, research: Boolean, assetNames: { type: Object, default: () => ({}) } })
const names = { annualReturn:'年化收益', annualizedReturn:'年化收益', totalReturn:'累计收益', maxDrawdown:'最大回撤', sharpe:'夏普比率', sharpeRatio:'夏普比率', volatility:'波动率', turnover:'换手率', totalFees:'总费用', totalSlippage:'总滑点', benchmarkReturn:'基准收益', excessReturn:'超额收益', initialCash:'初始资金', finalEquity:'最终权益' }
const charts = computed(() => {
  mode.value; themeColor.value
  const theme = getChartTheme()
  const result = props.result
  const comparisons = [{ name: '策略', result },
    ...(result.buyAndHold?.status === 'READY' ? [{name:'买入并持有', result:result.buyAndHold}] : []),
    ...(result.trackingIndex?.status === 'READY' ? [{name:`跟踪指数 · ${result.trackingIndex.name}`, result:result.trackingIndex}] : []),
  ]
  const curve = item => item.chartEquityCurve || item.equityCurve || item.equity || []
  const months = [...new Set(comparisons.flatMap(item => (item.result.monthlyReturns || []).map(row => row.month)))].sort()
  const percent = value => value == null ? '—' : `${(Number(value)*100).toFixed(2)}%`
  const number = value => value == null ? '—' : Number(value).toLocaleString('zh-CN', {maximumFractionDigits:2})
  const palette = [theme.palette[0], theme.palette[1], theme.palette[2]]
  return [['equity','收益走势'],['drawdown','回撤'],['monthly','月度收益']].map(([key,title]) => {
    const monthly = key === 'monthly'
    const formatter = key === 'equity' ? number : percent
    const series = comparisons.map((item, index) => ({name:item.name, type:monthly?'bar':'line', showSymbol:false, connectNulls:false,
      ...(monthly ? {} : {emphasis:{disabled:true}}),
      ...(monthly
        ? {barMaxWidth:18, barGap:'22%', barCategoryGap:'50%', itemStyle:{color:palette[index],borderRadius:[3,3,0,0]}}
        : {symbol:'circle', symbolSize:6, lineStyle:{color:palette[index],width:key==='equity'&&index===0?2.8:2, type:index===2?'dashed':'solid',opacity:index===2?0.85:1},itemStyle:{color:palette[index]}}),
      data:monthly ? months.map(month => (item.result.monthlyReturns || []).find(row => row.month === month)?.return ?? null)
        : (key==='drawdown' && !item.result.chartEquityCurve ? item.result.drawdown || curve(item.result) : curve(item.result))
          .map(row => Array.isArray(row) ? row : [row.date || row.timestamp, row[key] ?? (key==='equity'?row.value:null)]),
      tooltip:{valueFormatter:formatter},
    }))
    return {key,title,available:monthly?months.length>0:series.some(item=>item.data.length),option:{
      color:palette,textStyle:{color:theme.foreground},animation:false,
      tooltip:{...theme.tooltip,confine:true,padding:[10,14],borderRadius:10,
        axisPointer:monthly?{type:'shadow',shadowStyle:{color:theme.border,opacity:.22}}:{type:'line',lineStyle:{color:theme.mutedForeground,type:'dashed',width:1,opacity:.55}}},
      legend:{data:comparisons.map(item=>item.name),type:'scroll',top:4,left:8,right:8,
        icon:'circle',itemWidth:9,itemHeight:9,itemGap:22,textStyle:{color:theme.mutedForeground,fontSize:12,padding:[0,0,0,3]}},
      grid:{left:12,right:18,top:48,bottom:14,containLabel:true},
      xAxis:{type:monthly?'category':'time',...(monthly?{data:months,boundaryGap:true}:{}),
        axisLine:{show:false},axisTick:{show:false},splitLine:{show:false},
        axisLabel:{...theme.axisLabel,hideOverlap:true,margin:14,fontSize:11}},
      yAxis:{type:'value',scale:key==='equity',splitNumber:key==='equity'?4:3,
        axisLine:{show:false},axisTick:{show:false},
        splitLine:{show:true,lineStyle:{color:theme.border,type:'dashed',width:1,opacity:.55}},
        axisLabel:{...theme.axisLabel,formatter,margin:14,fontSize:11}},series,
    }}
  })
})
const sections = [['positions','持仓'],['fills','成交记录'],['orders','委托记录'],['cashLedger','现金记录'],['coverage','数据覆盖']]
const holdComparison = computed(() => buyAndHoldComparison(props.result))
const percentKeys = new Set(['netReturn','annualReturn','annualizedReturn','totalReturn','maxDrawdown','volatility','turnover','benchmarkReturn','excessReturn'])
const metricValue = (key,value) => typeof value !== 'number' ? format(value) : percentKeys.has(key) ? `${(value*100).toFixed(2)}%` : value.toLocaleString(undefined,{maximumFractionDigits:4})
const namedRows = rows => Array.isArray(rows) ? rows.map(row=>row?.assetId?{...row,name:props.assetNames[row.assetId]||row.name||'资产名称未记录'}:row) : rows
const factorRows = computed(() => (props.result.factors || []).map(({groupReturns,key,...row}) => ({factor:key,...row})))

const primaryGroups=[['totalReturn','netReturn'],['annualReturn','annualizedReturn'],['maxDrawdown'],['fees','totalFees'],['tradeCount']]
const secondaryKeys=new Set(['volatility','turnover','observations'])
const performanceMetrics=computed(()=>props.result.metrics||{})
const hasPerformance=computed(()=>['netReturn','totalReturn','annualReturn','maxDrawdown'].some(key=>props.result.metrics?.[key]!=null))
const primaryMetrics=computed(()=>primaryGroups.map(keys=>{
 const key=keys.find(key=>performanceMetrics.value[key]!=null)||keys[0]
 return {key,label:key==='netReturn'?'累计收益':names[key]||metricNames[key],value:metricValue(key,performanceMetrics.value[key])}
}))
const metricItems=entries=>entries.map(([key,value])=>({key,label:metricNames[key]||names[key]||key,value:metricValue(key,value)}))
const secondaryMetrics=computed(()=>metricItems(Object.entries(props.result.metrics||{}).filter(([key])=>secondaryKeys.has(key))))
const professionalMetrics=computed(()=>metricItems(Object.entries(props.result.metrics||{}).filter(([key])=>['holdoutMse','baselineMse','holdoutIC','trainSamples','validationSamples','holdoutSamples','evaluatedThrough'].includes(key))))
const portfolioMetrics=computed(()=>{
 const result=props.result
 const equity=result.valuation?.equity ?? result.equityCurve?.at(-1)?.equity
 const receivables=(result.receivables||[]).reduce((sum,r)=>sum+Number(r.amount||0),0)
 return [{key:'equity',label:'当前权益',value:equity==null?'尚未估值':metricValue('cash',equity)},
 {key:'cash',label:'可用资金',value:metricValue('cash',result.cash)},
 {key:'positions',label:'持仓市值',value:equity==null?'—':metricValue('cash',Number(equity)-Number(result.cash||0)-receivables)},
 {key:'receivables',label:'待到账资金',value:metricValue('cash',receivables)}]
})
</script>
<template>
  <div>
    <section v-if="hasPerformance" class="quant-section"><h3>表现摘要</h3><MetricGrid :items="primaryMetrics" primary /></section>
    <section v-else-if="professionalMetrics.length" class="quant-section"><h3>模型评估</h3><MetricGrid :items="professionalMetrics" /></section>
    <slot name="summary" />
    <section v-if="holdComparison" class="quant-section"><h3>表现对比</h3><MetricGrid v-if="holdComparison.items.length" :items="holdComparison.items" /><p v-else class="muted">{{ holdComparison.message }}</p></section>
    <template v-if="result.equityCurve?.length || result.equity?.length">
      <section v-for="chart in charts" :key="chart.key" class="panel quant-chart-panel" :class="`quant-chart-panel--${chart.key}`"><h3>{{ chart.title }}</h3><VChart v-if="chart.available" :option="chart.option" autoresize class="quant-chart" :class="`quant-chart--${chart.key}`" /><p v-else class="muted">本次结果未记录月度收益。</p></section>
    </template>
    <section v-if="result.cash!=null" class="quant-section"><h3>资金概况</h3><MetricGrid :items="portfolioMetrics" /></section>
    <p v-if="result.valuation?.dataState==='INCOMPLETE'" class="muted mb-4">部分资产行情尚未到齐，组合交易仅处理至 {{ result.valuation.completeThrough || '暂无完整日期' }}。</p>
    <section v-if="secondaryMetrics.length" class="quant-section"><h3>交易统计</h3><MetricGrid :items="secondaryMetrics" /></section>
    <section v-if="factorRows.length" class="panel"><h3>因子效果</h3><DataTable :rows="factorRows" /></section>
    <section v-for="factor in result.factors || []" :key="factor.key" class="panel"><h3>{{ label(factor.key) }} · 分组未来收益</h3><DataTable :rows="factor.groupReturns || []" /></section>
    <details v-for="[key,title] in sections.filter(([key]) => result[key] != null && (!Array.isArray(result[key]) || result[key].length > 0))" :key="key" class="panel" :open="key==='positions'||key==='fills'"><summary>{{ title }}</summary><OrderTable v-if="key==='orders'" :rows="namedRows(result[key])" /><DataTable v-else :rows="namedRows(result[key])" /></details>
    <slot name="research" />
  </div>
</template>
