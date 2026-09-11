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
const props = defineProps({ result: { type: Object, default: () => ({}) }, hideQualificationStatus: Boolean, research: Boolean })
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
    legend:{data:['权益',...(benchmark?['基准权益']:[]),'回撤'],top:0,bottom:'auto',left:'center',textStyle:{color:theme.foreground}},
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
      ...(benchmark?[{name:'基准权益',type:'line',showSymbol:false,lineStyle:{type:'dashed'},data:points(benchmark.equityCurve||[],'equity'),tooltip:{valueFormatter:numberText}}]:[]),
      {name:'回撤',type:'line',showSymbol:false,xAxisIndex:1,yAxisIndex:1,areaStyle:{opacity:.15},
        data:points(drawdown,'drawdown'),tooltip:{valueFormatter:value=>`${(Number(value)*100).toFixed(2)}%`}},
    ],
  }
})
const sections = [['positions','持仓'],['orders','委托'],['fills','成交'],['signals','策略信号'],['positionHistory','持仓变化'],['targetHistory','目标仓位'],['cashLedger','现金账本'],['costs','成本明细'],['coverage','数据覆盖'],['factorResults','因子 IC / ICIR'],['groupReturns','因子分组收益']]
const percentKeys = new Set(['netReturn','annualReturn','annualizedReturn','totalReturn','maxDrawdown','volatility','turnover','benchmarkReturn','excessReturn'])
const metricValue = (key,value) => typeof value !== 'number' ? format(value) : percentKeys.has(key) ? `${(value*100).toFixed(2)}%` : value.toLocaleString(undefined,{maximumFractionDigits:4})
const factorRows = computed(() => (props.result.factors || []).map(({groupReturns,...row}) => row))
const correlations = computed(() => (props.result.correlation?.keys || []).map((key,i) => Object.fromEntries([['factor',key],...props.result.correlation.keys.map((other,j)=>[other,props.result.correlation.matrix?.[i]?.[j]])])))

const primaryGroups=[['totalReturn','netReturn'],['annualReturn','annualizedReturn'],['maxDrawdown'],['benchmarkReturn'],['excessReturn']]
const primaryKeys=new Set(primaryGroups.flat())
const secondaryKeys=new Set(['volatility','turnover','tradeCount','observations','fees','totalFees','totalSlippage'])
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
const professionalMetrics=computed(()=>metricItems(Object.entries(props.result.metrics||{}).filter(([key])=>!primaryKeys.has(key)&&!secondaryKeys.has(key))))
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
    <div v-if="!research&&((!hideQualificationStatus&&result.qualification?.status)||result.qualification?.reasons?.length)" class="quant-validation"><QuantStatusBadge v-if="!hideQualificationStatus" :status="result.qualification?.status" /><p v-for="(reason,index) in result.qualification?.reasons||[]" :key="index" class="muted">{{label(reason)}}</p></div>
    <section v-if="result.cash!=null" class="quant-section"><h3>资金概况</h3><MetricGrid :items="portfolioMetrics" /></section>
    <p v-if="result.valuation?.dataState==='INCOMPLETE'" class="muted mb-4">部分资产行情尚未到齐。当前显示各资产最新已知价格估值；组合交易仅处理至 {{ result.valuation.completeThrough || '暂无完整日期' }}，等待完整行情后再推进。</p>
    <section v-if="hasPerformance" class="quant-section"><h3>表现摘要</h3><MetricGrid :items="primaryMetrics" primary /><p v-if="result.benchmark?.status==='READY'" class="muted">基准：{{result.benchmark.name}}</p></section>
    <section v-else-if="professionalMetrics.length" class="quant-section"><h3>分析摘要</h3><MetricGrid :items="professionalMetrics.slice(0,4)" primary /></section>
    <div v-if="result.equityCurve?.length || result.equity?.length || result.drawdown?.length" class="panel"><h3>权益与回撤</h3><VChart :option="chart" autoresize class="quant-chart" /></div>
    <slot name="research" />
    <section v-if="secondaryMetrics.length" class="quant-section"><h3>风险与交易统计</h3><MetricGrid :items="secondaryMetrics" /></section>
    <details v-if="professionalMetrics.length" class="quant-secondary"><summary>更多指标</summary><MetricGrid :items="professionalMetrics" /></details>
    <div v-if="factorRows.length" class="panel"><h3>因子效果</h3><DataTable :rows="factorRows" /></div>
    <div v-if="correlations.length" class="panel"><h3>因子相关性</h3><DataTable :rows="correlations" /></div>
    <div v-for="factor in result.factors || []" :key="factor.key" class="panel"><h3>{{ factor.key }} · 分组未来收益</h3><DataTable :rows="factor.groupReturns || []" /></div>
    <details v-if="!research&&result.assumptions?.length" class="quant-secondary"><summary>模拟假设与有效性限制</summary><p v-for="(item,index) in result.assumptions" :key="index" class="muted">{{ format(item) }}</p></details>
    <details v-for="[key,title] in sections.filter(([key]) => result[key] != null && (!Array.isArray(result[key]) || result[key].length > 0))" :key="key" class="quant-secondary" :open="key==='positions'"><summary>{{ title }}<span v-if="Array.isArray(result[key])" class="muted ml-2">{{result[key].length}} 条</span></summary><OrderTable v-if="key==='orders'" :rows="result[key]" /><DataTable v-else :rows="result[key]" /></details>
    <details v-if="!research&&result.provenance" class="quant-secondary"><summary>数据来源与复现记录</summary><pre class="mt-3">{{ JSON.stringify(result.provenance,null,2) }}</pre></details>
    <details v-if="Object.keys(result).length" class="quant-secondary"><summary>完整报告</summary><pre class="mt-3">{{ JSON.stringify(result,null,2) }}</pre></details>
  </div>
</template>
