<script setup>
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import QuantStatusBadge from './QuantStatusBadge.vue'
import DataTable from './DataTable.vue'
import { explainExperiment } from './experimentExplanation.js'
import { parameterDisplayValue } from './parameterPresentation.js'
import { label, metricValue } from './shared.js'

const props = defineProps({ detail: { type: Object, required: true } })
const report = computed(() => explainExperiment(props.detail))
const summary = computed(() => props.detail.summary || null)
const evidence = computed(() => props.detail.evidence || null)
const provenance = computed(() => props.detail.provenance || {})
const runs = computed(() => [...(props.detail.runs || [])].sort((a, b) => a.ordinal - b.ordinal))
const stableRangeText = computed(() => summary.value?.stableRange?.length
  ? summary.value.stableRange.map(value => parameterDisplayValue(props.detail.parameter?.key, value)).join(' ～ ') : '未记录')
const evidenceAxes = computed(() => [
  ['控制变量完整性', evidence.value?.controlIntegrity], ['数据一致性', evidence.value?.dataConsistency],
  ['来源完整性', evidence.value?.sourceCompleteness], ['运行覆盖', evidence.value?.runCoverage],
])
const metric = (key, value) => value == null ? '—' : metricValue(key, value)
</script>

<template>
  <section class="quant-section quant-research-summary" aria-label="研究结论">
    <h3>研究结论</h3>
    <p v-if="!summary" class="muted">研究结论将在候选运行完成后生成。</p>
    <template v-else>
      <dl class="quant-metrics quant-metrics-primary">
        <div><dt class="muted">参数稳定性</dt><dd>{{ report.classificationText }}</dd></div>
        <div><dt class="muted">当前值</dt><dd>{{ report.baselineText }}</dd></div>
        <div><dt class="muted">稳定范围</dt><dd>{{ stableRangeText }}</dd></div>
        <div><dt class="muted">候选结果表现</dt><dd>{{ report.performanceProfileText }}</dd></div>
        <div><dt class="muted">证据质量</dt><dd>{{ report.evidenceQualityText }}</dd></div>
      </dl>
      <p v-if="report.conclusionNotice" class="mt-3 text-sm font-medium">{{ report.conclusionNotice }}</p>
    </template>
  </section>

  <section class="panel quant-section">
    <h3>五点参数结果</h3>
    <div v-if="runs.length" class="table-wrap">
      <Table><TableHeader><TableRow><TableHead>参数值</TableHead><TableHead>执行状态</TableHead><TableHead>净收益</TableHead><TableHead>最大回撤</TableHead><TableHead>波动率</TableHead><TableHead>换手率</TableHead><TableHead>运行验证</TableHead></TableRow></TableHeader>
        <TableBody><TableRow v-for="run in runs" :key="run.id"><TableCell><span>{{ parameterDisplayValue(detail.parameter?.key, run.value) }}</span><Badge v-if="run.baseline" variant="outline" class="ml-2">当前值</Badge></TableCell><TableCell><QuantStatusBadge :status="run.status" /></TableCell><TableCell>{{ metric('netReturn',run.metrics?.netReturn) }}</TableCell><TableCell>{{ metric('maxDrawdown',run.metrics?.maxDrawdown) }}</TableCell><TableCell>{{ metric('volatility',run.metrics?.volatility) }}</TableCell><TableCell>{{ metric('turnover',run.metrics?.turnover) }}</TableCell><TableCell><QuantStatusBadge :status="run.qualification?.status" /><span v-if="!run.qualification?.status">—</span></TableCell></TableRow></TableBody>
      </Table>
    </div>
    <p v-else class="muted">候选运行尚未创建。</p>
  </section>

  <details v-if="summary" class="panel quant-research-section"><summary>稳定性判断依据</summary>
    <dl class="quant-detail-meta"><div><dt class="muted">稳定范围</dt><dd>{{ stableRangeText }}</dd></div><div><dt class="muted">局部方向</dt><dd>{{ report.directionText }}</dd></div><div><dt class="muted">方向一致性</dt><dd>{{ report.directionConsistencyText }}</dd></div><div><dt class="muted">收益容差</dt><dd>{{ metric('netReturn',summary.returnTolerance) }}</dd></div><div><dt class="muted">回撤容差</dt><dd>{{ metric('maxDrawdown',summary.drawdownTolerance) }}</dd></div><div><dt class="muted">孤立峰值</dt><dd>{{ summary.isolatedPeak == null ? '未记录' : summary.isolatedPeak ? '是' : '否' }}</dd></div></dl>
    <p class="muted mt-3">{{ report.algorithmNotice }}</p>
    <ul v-if="summary.reasons?.length" class="quant-research-list"><li v-for="reason in summary.reasons" :key="reason">{{ label(reason) }}<code v-if="label(reason)!==reason" class="block muted">{{ reason }}</code></li></ul>
    <details v-if="summary.localSensitivity" class="quant-secondary"><summary>局部敏感性参数</summary><DataTable :rows="summary.localSensitivity" /></details>
  </details>

  <details class="panel quant-research-section"><summary>运行验证详情</summary>
    <p class="muted">{{ report.qualificationNotice }}</p>
    <dl v-if="summary?.qualificationSummary" class="quant-detail-meta mt-3"><div><dt class="muted">验证通过</dt><dd>{{ summary.qualificationSummary.qualified ?? '未记录' }}</dd></div><div><dt class="muted">验证未通过</dt><dd>{{ summary.qualificationSummary.unqualified ?? '未记录' }}</dd></div><div><dt class="muted">验证缺失</dt><dd>{{ summary.qualificationSummary.missing ?? '未记录' }}</dd></div></dl>
    <details v-for="run in runs.filter(item=>item.qualification||item.validation||item.error)" :key="run.id" class="quant-secondary"><summary>{{ parameterDisplayValue(detail.parameter?.key,run.value) }} · {{ run.baseline?'当前值':'候选值' }}</summary><dl class="quant-detail-meta"><div><dt class="muted">成交数</dt><dd>{{ run.metrics?.tradeCount ?? '未记录' }}</dd></div><div><dt class="muted">验证范围</dt><dd>{{ run.qualification?.scope || '未记录' }}</dd></div><div><dt class="muted">控制变量校验</dt><dd>{{ run.validation?.code || (run.validation?.valid===true?'通过':'未记录') }}</dd></div><div><dt class="muted">运行错误</dt><dd>{{ run.error?.code || '无记录' }}</dd></div></dl><ul v-if="run.qualification?.reasons?.length" class="quant-research-list"><li v-for="reason in run.qualification.reasons" :key="reason">{{ label(reason) }}<code v-if="label(reason)!==reason" class="block muted">{{ reason }}</code></li></ul></details>
  </details>

  <details class="panel quant-research-section"><summary>数据与运行环境</summary>
    <dl v-if="evidence" class="quant-detail-meta"><div><dt class="muted">证据质量</dt><dd>{{ report.evidenceQualityText }}</dd></div><div><dt class="muted">有效运行</dt><dd>{{ evidence.validRuns ?? '未记录' }} / {{ evidence.totalRuns ?? '未记录' }}</dd></div><div v-for="[title,value] in evidenceAxes" :key="title"><dt class="muted">{{ title }}</dt><dd>{{ report.evidenceAxisText(value) }}</dd></div></dl><details v-if="evidence?.reasonClassifications&&Object.keys(evidence.reasonClassifications).length" class="quant-secondary"><summary>证据原因分类</summary><DataTable :rows="evidence.reasonClassifications" /></details>
    <div v-if="provenance.snapshot?.metadata" class="quant-secondary"><h3>研究数据快照</h3><p class="muted">{{ provenance.snapshot.metadata.assetCount ?? '未记录' }} 个资产 · {{ provenance.snapshot.metadata.startDate || '未记录' }} ～ {{ provenance.snapshot.metadata.endDate || '未记录' }}</p><details><summary>查看完整快照元数据</summary><DataTable :rows="provenance.snapshot.metadata" /></details></div>
    <details class="quant-secondary"><summary>技术信息</summary><dl class="quant-detail-meta"><div><dt class="muted">候选规则版本</dt><dd>{{ provenance.candidateRuleVersion || '未记录' }}</dd></div><div><dt class="muted">稳定性算法版本</dt><dd>{{ provenance.stabilityAlgorithmVersion || '未记录' }}</dd></div><div><dt class="muted">证据版本</dt><dd>{{ provenance.evidenceSchemaVersion || '未记录' }}</dd></div><div><dt class="muted">快照校验哈希</dt><dd>{{ provenance.snapshot?.contentHash || '未记录' }}</dd></div></dl><details v-if="provenance.sourceRuntime||provenance.experimentRuntime"><summary>运行环境</summary><DataTable :rows="{sourceRuntime:provenance.sourceRuntime,experimentRuntime:provenance.experimentRuntime}" /></details><details v-if="provenance.assumptions?.length"><summary>研究假设</summary><DataTable :rows="provenance.assumptions" /></details><details v-if="provenance.benchmarkContract"><summary>基准合同</summary><DataTable :rows="provenance.benchmarkContract" /></details></details>
  </details>
</template>
