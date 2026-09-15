<script setup>
import QuantStatusBadge from './QuantStatusBadge.vue'
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import MetricGrid from './MetricGrid.vue'
import DataTable from './DataTable.vue'
import OrderTable from './OrderTable.vue'
import { label, format, metricNames } from './shared'
import { getChartTheme } from '@/lib/chartTheme'
import { useAppearance } from '@/composables/useAppearance'
const { mode, themeColor } = useAppearance()
use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])
const props = defineProps({ result: { type: Object, default: () => ({}) }, hideQualificationStatus: Boolean, research: Boolean, assetNames: { type: Object, default: () => ({}) } })
const names = { annualReturn:'年化收益', annualizedReturn:'年化收益', totalReturn:'累计收益', maxDrawdown:'最大回撤', sharpe:'夏普比率', sharpeRatio:'夏普比率', volatility:'波动率', turnover:'换手率', totalFees:'总费用', totalSlippage:'总滑点', benchmarkReturn:'基准收益', excessReturn:'超额收益', initialCash:'初始资金', finalEquity:'最终权益' }
const chart = computed(() => {
  mode.value; themeColor.value
  const theme = getChartTheme()
  const equity = props.result.equityCurve || props.result.equity || []
  const benchmark = props.result.benchmark?.status === 'READY' ? props.result.benchmark : null
  const drawdown = props.result.drawdown || equity
  const points = (rows, field) => Array.isArray(rows) ? rows.map((r, i) => Array.isArray(r) ? r : [r.date || r.timestamp || i, r[field] ?? r.value ?? null]) : []
  const axis = {axisLabel:theme.axisLabel,axisLine:theme.axisLine,splitLine:theme.splitLine}
  const numberText = value => Number.isFinite(Number(value)) ? Number(value).toLocaleString('zh-CN', {maximumFractionDigits:2}) : '—'
  return {
    color:theme.palette, textStyle:{color:theme.foreground},
    tooltip:{...theme.tooltip, confine:true, backgroundColor:theme.card,
      extraCssText:theme.tooltip.extraCssText+' opacity:1;',
    },
    legend:{data:['权益',...(benchmark?['研究基准权益']:[]),'回撤'],top:0,bottom:'auto',left:'center',textStyle:{color:theme.foreground}},
    grid:[
      {left:12,right:20,top:42,height:'43%',containLabel:true},
      {left:12,right:20,top:'67%',bottom:12,containLabel:true},
    ],
    xAxis:[0,1].map(gridIndex=>({...axis,type:'category',gridIndex,
      axisLabel:{...theme.axisLabel,hideOverlap:true,margin:12}})),
    yAxis:[
      {...axis,type:'value',scale:true,gridIndex:0,axisLabel:{...theme.axisLabel,formatter:numberText}},
      {...axis,type:'value',scale:true,gridIndex:1,axisLabel:{...theme.axisLabel,formatter:value=>`${(value*100).toFixed(1)}%`}},
    ],
    series:[
      {name:'权益',type:'line',showSymbol:false,data:points(equity,'equity'),
        tooltip:{valueFormatter:numberText}},
      ...(benchmark?[{name:'研究基准权益',type:'line',showSymbol:false,lineStyle:{type:'dashed'},data:points(benchmark.equityCurve||[],'equity'),tooltip:{valueFormatter:numberText}}]:[]),
      {name:'回撤',type:'line',showSymbol:false,xAxisIndex:1,yAxisIndex:1,areaStyle:{opacity:.15},
        data:points(drawdown,'drawdown'),tooltip:{valueFormatter:value=>`${(Number(value)*100).toFixed(2)}%`}},
    ],
  }
})
const sections = [['positions','持仓'],['fills','成交记录'],['orders','委托记录'],['cashLedger','现金记录'],['coverage','数据覆盖']]
const percentKeys = new Set(['netReturn','annualReturn','annualizedReturn','totalReturn','maxDrawdown','volatility','turnover','benchmarkReturn','excessReturn'])
const metricValue = (key,value) => typeof value !== 'number' ? format(value) : percentKeys.has(key) ? `${(value*100).toFixed(2)}%` : value.toLocaleString(undefined,{maximumFractionDigits:4})
const namedRows = rows => Array.isArray(rows) ? rows.map(row=>row?.assetId?{...row,name:props.assetNames[row.assetId]||row.name||'资产名称未记录'}:row) : rows
const factorRows = computed(() => (props.result.factors || []).map(({groupReturns,key,...row}) => ({factor:key,...row})))

const primaryGroups=[['totalReturn','netReturn'],['annualReturn','annualizedReturn'],['maxDrawdown'],['fees','totalFees'],['tradeCount']]
const secondaryKeys=new Set(['volatility','turnover','observations'])
const performanceMetrics=computed(()=>({...props.result.metrics,
 benchmarkReturn:props.result.metrics?.benchmarkReturn??(props.result.benchmark?.status==='READY'?props.result.benchmark.metrics?.netReturn:undefined),
 excessReturn:props.result.metrics?.excessReturn??(props.result.benchmark?.status==='READY'?props.result.benchmark.excessReturn:undefined)}))
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
    <div v-if="result.equityCurve?.length || result.equity?.length || result.drawdown?.length" class="panel"><h3>权益与回撤</h3><VChart :option="chart" autoresize class="quant-chart" /></div>
    <section v-if="result.cash!=null" class="quant-section"><h3>资金概况</h3><MetricGrid :items="portfolioMetrics" /></section>
    <p v-if="result.valuation?.dataState==='INCOMPLETE'" class="muted mb-4">部分资产行情尚未到齐，组合交易仅处理至 {{ result.valuation.completeThrough || '暂无完整日期' }}。</p>
    <section v-if="secondaryMetrics.length" class="quant-section"><h3>交易统计</h3><MetricGrid :items="secondaryMetrics" /></section>
    <section v-if="factorRows.length" class="panel"><h3>因子效果</h3><DataTable :rows="factorRows" /></section>
    <section v-for="factor in result.factors || []" :key="factor.key" class="panel"><h3>{{ label(factor.key) }} · 分组未来收益</h3><DataTable :rows="factor.groupReturns || []" /></section>
    <details v-if="result.benchmark?.status==='READY'" class="panel"><summary>研究基准比较</summary><p class="muted mb-3">匹配目标仓位的资产池等权比较</p><MetricGrid :items="[{key:'benchmark',label:'研究基准收益',value:metricValue('netReturn',performanceMetrics.benchmarkReturn)},{key:'difference',label:'相对研究基准',value:metricValue('netReturn',performanceMetrics.excessReturn)}]" /></details>
    <details v-for="[key,title] in sections.filter(([key]) => result[key] != null && (!Array.isArray(result[key]) || result[key].length > 0))" :key="key" class="panel" :open="key==='positions'||key==='fills'"><summary>{{ title }}</summary><OrderTable v-if="key==='orders'" :rows="namedRows(result[key])" /><DataTable v-else :rows="namedRows(result[key])" /></details>
    <slot name="research" />
  </div>
</template>
