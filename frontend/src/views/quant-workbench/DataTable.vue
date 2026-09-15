<script setup>
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { computed } from 'vue'
import { format, label } from './shared'
const props = defineProps({ rows: { type: [Array, Object], default: () => [] } })
const records = computed(() => Array.isArray(props.rows) ? props.rows : Object.entries(props.rows || {}).map(([key, value]) => ({ key, value })))
const columns = computed(() => [...new Set(records.value.flatMap(row => row && typeof row === 'object' ? Object.keys(row).filter(key=>titles[key]&&!['id','productId','assetId','orderId','strategyVersionId','assetIds','key','method'].includes(key)&&typeof row[key]!=='object') : ['value']))])
const titles = { date:'日期', timestamp:'时间', productId:'标的 ID', assetId:'资产 ID', code:'代码', name:'名称', side:'方向', status:'状态', quantity:'数量', price:'价格', fee:'费用', cash:'现金', equity:'权益', drawdown:'回撤', key:'字段', value:'值', weight:'权重', reason:'原因', amount:'金额', totalCost:'总成本', slippage:'滑点', orderId:'委托 ID', factor:'因子', ic:'IC', rankIc:'Rank IC', icir:'ICIR', meanIc:'平均 IC', groups:'分组', availableCash:'可用资金', settledCash:'已结算资金' }
Object.assign(titles,{rankIC:'Rank IC',icIR:'IC 信息比率',sampleCount:'样本数',marketValue:'市值',coverage:'覆盖率',usableSamples:'有效样本',observations:'历史记录数',meanForwardReturn:'平均未来收益',group:'分组',dates:'日期数',method:'计算方法',qualification:'有效性',revision:'版本',createdAt:'创建时间',updatedAt:'更新时间',assetClass:'资产类别',assetIds:'成员',strategyType:'策略类型',strategyVersionId:'策略版本',unitPrice:'单位价格',settlementDate:'到账日期',availableDate:'可用日期',balance:'余额',notional:'成交金额',commission:'佣金',signalDate:'信号日期',executionDate:'成交日期',receivables:'待到账资金'})
Object.assign(titles,{type:'收支类型', cost:'持仓成本', lastPrice:'最近价格', id:'记录编号',filledQuantity:'已成交数量',confirmedDate:'确认日期'})
const display = (col,value) => col==='qualification' ? (value==='QUALIFIED'?'符合本次检查条件':value==='UNQUALIFIED'?'未满足本次检查条件':'未记录') : ['status','side','assetClass','strategyType','reason','factor','type'].includes(col) ? label(value) : typeof value==='number' ? ['drawdown','weight','coverage','meanForwardReturn'].includes(col) ? `${(value*100).toFixed(2)}%` : value.toLocaleString(undefined,{maximumFractionDigits:6}) : format(value)
</script>
<template>
  <div v-if="records.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead v-for="col in columns" :key="col">{{ titles[col] || col }}</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="(row, index) in records" :key="index"><TableCell v-for="col in columns" :key="col">{{ display(col,row && typeof row === 'object' ? row[col] : row) }}</TableCell></TableRow></TableBody></Table></div>
  <p v-else class="empty">暂无记录</p>
</template>
