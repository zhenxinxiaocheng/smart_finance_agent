<script setup>
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import QuantStatusBadge from './QuantStatusBadge.vue'
import { explainExperiment } from './experimentExplanation.js'
import { parameterDisplayValue } from './parameterPresentation.js'
import { metricValue } from './shared.js'
import { userMessage } from './presentation.js'
const props = defineProps({ detail: { type: Object, required: true } })
const report = computed(() => explainExperiment(props.detail))
const summary = computed(() => props.detail.summary)
const runs = computed(() => [...(props.detail.runs || [])].sort((a,b)=>a.ordinal-b.ordinal))
const range = computed(() => summary.value?.stableRange?.length ? summary.value.stableRange.map(v=>parameterDisplayValue(props.detail.parameter?.key,v)).join(' ～ ') : '未记录')
const metric = (key,value) => value == null ? '—' : metricValue(key,value)
const runIssues = computed(() => runs.value.flatMap(run => {
  const messages = [...new Set((run.qualification?.reasons||[]).map(code=>userMessage(code,code,'部分模拟条件未满足。'))) ]
  if(run.error) messages.push(userMessage(run.error.message,run.error.code,'该候选运行未完成，请稍后重新检查。'))
  if(run.validation?.valid===false) messages.push('本次候选未通过一致性检查，不能用于可靠比较。')
  return messages.map(message=>({value:parameterDisplayValue(props.detail.parameter?.key,run.value),message}))
}))
</script>
<template>
  <section class="quant-section" aria-label="研究结论">
    <h3>研究结论</h3>
    <p v-if="!summary" class="muted">研究结论将在候选运行完成后生成。</p>
    <template v-else>
      <dl class="quant-metrics quant-metrics-primary">
        <div><dt class="muted">参数稳定性</dt><dd>{{ report.classificationText }}</dd></div>
        <div><dt class="muted">当前值</dt><dd>{{ report.baselineText }}</dd></div>
        <div><dt class="muted">稳定范围</dt><dd>{{ range }}</dd></div>
        <div><dt class="muted">候选结果表现</dt><dd>{{ report.performanceProfileText }}</dd></div>
      </dl>
      <p v-if="report.conclusionNotice" class="mt-3 text-sm">{{ report.conclusionNotice }}</p>
      <p v-if="['INVALID','INSUFFICIENT','LOW'].includes(detail.evidence?.quality)" class="muted mt-3">本次证据不完整，参数稳定性结论需谨慎使用。</p>
      <p v-if="detail.evidence?.validRuns!=null" class="muted mt-3">纳入比较 {{ detail.evidence.validRuns }} / {{ detail.evidence.totalRuns??runs.length }} 次运行</p>
    </template>
  </section>
  <section class="panel">
    <h3>五点参数结果</h3>
    <div v-if="runs.length" class="table-wrap"><Table><TableHeader><TableRow><TableHead>参数值</TableHead><TableHead>执行状态</TableHead><TableHead>净收益</TableHead><TableHead>最大回撤</TableHead><TableHead>波动率</TableHead><TableHead>换手率</TableHead><TableHead>模拟条件</TableHead></TableRow></TableHeader>
      <TableBody><TableRow v-for="run in runs" :key="run.id"><TableCell>{{ parameterDisplayValue(detail.parameter?.key,run.value) }}<Badge v-if="run.baseline" variant="outline" class="ml-2">当前值</Badge></TableCell><TableCell><QuantStatusBadge :status="run.status" /></TableCell><TableCell>{{ metric('netReturn',run.metrics?.netReturn) }}</TableCell><TableCell>{{ metric('maxDrawdown',run.metrics?.maxDrawdown) }}</TableCell><TableCell>{{ metric('volatility',run.metrics?.volatility) }}</TableCell><TableCell>{{ metric('turnover',run.metrics?.turnover) }}</TableCell><TableCell>{{ run.qualification?.status==='QUALIFIED'?'可继续模拟':run.qualification?.status==='UNQUALIFIED'?'暂不能继续模拟':'尚无结论' }}</TableCell></TableRow></TableBody>
    </Table></div>
    <p v-else class="muted">候选运行尚未创建。</p>
  </section>
  <section v-if="runIssues.length" class="quant-section"><h3>需要注意</h3><ul class="quant-research-list"><li v-for="(item,index) in runIssues" :key="index">{{ item.value }}：{{ item.message }}</li></ul></section>
  <details v-if="summary" class="panel"><summary>稳定性判断依据</summary>
    <dl class="quant-detail-meta"><div><dt class="muted">局部方向</dt><dd>{{ report.directionText }}</dd></div><div><dt class="muted">方向一致性</dt><dd>{{ report.directionConsistencyText }}</dd></div><div><dt class="muted">收益容差</dt><dd>{{ metric('netReturn',summary.returnTolerance) }}</dd></div><div><dt class="muted">回撤容差</dt><dd>{{ metric('maxDrawdown',summary.drawdownTolerance) }}</dd></div></dl>
  </details>
</template>
