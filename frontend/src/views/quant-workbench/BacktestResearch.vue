<script setup>
import { computed } from 'vue'
import { Button } from '@/components/ui/button'
import QuantStatusBadge from './QuantStatusBadge.vue'
import DataTable from './DataTable.vue'
import { qualificationNotice, researchValue } from './researchExplanation.js'

const props = defineProps({ report: { type: Object, required: true }, summary: Boolean })
const range = value => `${researchValue(value?.startDate)} ～ ${researchValue(value?.endDate)}`
const lineageNames = { strategy: '策略', universe: '资产池', factorSet: '因子组合' }
const sources = computed(() => Object.entries(props.report.lineage).map(([key, item]) => ({ key, ...item })))
const sourceRows = computed(() => props.report.dataSnapshot.map(a => ({
  '资产': a.name || a.id, '代码': a.code, '资产 ID': a.id,
  '快照开始': a.startDate, '快照结束': a.endDate, '记录数': a.observations,
  '已记录来源': a.sources?.length ? a.sources.join('、') : '未记录',
  '成交价格复权标记': a.adjustTypes?.length ? a.adjustTypes.join('、') : '未记录',
})))
const technicalRows = computed(() => [
  ['策略 ID', props.report.provenance.strategyId],
  ['策略版本记录 ID', props.report.provenance.strategyVersionId],
  ['资产池 ID', props.report.provenance.universeId],
  ['资产池版本记录 ID', props.report.provenance.universeVersionId ?? props.report.provenance.universeVersion],
  ['因子组合版本记录 ID', props.report.provenance.factorSetVersionId ?? props.report.provenance.factorVersionId],
  ['引擎版本', props.report.provenance.engineVersion], ['引擎文件哈希', props.report.provenance.codeHash],
  ['数据哈希', props.report.provenance.dataHash], ['配置哈希', props.report.provenance.configHash],
  ['随机种子', props.report.provenance.seed],
].map(([label, value]) => ({ label, value })))
</script>

<template>
  <section v-if="summary" class="quant-section quant-research-summary" aria-label="研究结论">
    <h3>研究结论</h3>
    <p class="flex flex-wrap items-center gap-2"><QuantStatusBadge v-if="report.status" :status="report.status" /><span v-else>{{ report.statusText }}</span></p>
    <ul v-if="report.reasons.length" class="quant-research-list"><li v-for="r in report.reasons.slice(0,3)" :key="r.code">{{ r.message }}</li></ul>
    <p v-else-if="report.status==='UNQUALIFIED'" class="muted mt-2">引擎未记录具体原因，不能进一步解释未通过的检查。</p>
    <p v-if="report.reasons.length>3" class="muted mt-2">另有 {{ report.reasons.length-3 }} 项原因，可展开验证详情查看。</p>
    <div v-if="report.warnings.length" class="mt-3 text-sm"><span class="font-medium">重要风险：</span>{{ report.warnings.slice(0,2).map(w=>w.message).join('；') }}<a href="#backtest-warnings" class="link ml-2">查看 {{ report.warnings.length }} 项提示</a></div>
    <p class="muted mt-3">{{ qualificationNotice }}</p>
    <a href="#backtest-validation" class="link text-sm">查看验证范围和详情</a>
  </section>

  <div v-else>
    <section id="backtest-validation" class="quant-section quant-research-section">
      <h3>验证详情</h3>
      <p class="muted">{{ report.scopeMeaning }}</p>
      <details class="quant-secondary"><summary>验证状态与原因<span class="muted ml-2">{{ report.statusText }}</span></summary>
        <p v-if="report.reasons.length===0" class="muted">未记录失败原因；这不等于每项研究风险都已排除。</p>
        <ul v-else class="quant-research-list"><li v-for="r in report.reasons" :key="r.code">{{ r.message }}<code class="block muted">{{ r.code }}</code></li></ul>
        <dl class="quant-detail-meta mt-3"><div><dt class="muted">原始 qualification status</dt><dd>{{ researchValue(report.qualification.status) }}</dd></div><div><dt class="muted">原始 scope</dt><dd>{{ researchValue(report.qualification.scope) }}</dd></div></dl>
        <details><summary>原始验证记录</summary><pre>{{ JSON.stringify(report.qualification,null,2) }}</pre></details>
      </details>
    </section>

    <section class="quant-section quant-research-section">
      <h3>研究假设</h3>
      <dl v-if="report.assumptionSummary.length" class="quant-detail-meta"><div v-for="item in report.assumptionSummary" :key="item.key"><dt class="muted">{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl>
      <p v-else class="muted">未记录有效执行参数，不能用当前默认设置还原本次假设。</p>
      <p v-if="report.effectiveConfig.maxWeight!=null||report.effectiveConfig.maxDrawdown!=null" class="muted mt-3">目标权重限制不保证价格变化后的持仓权重；回撤阈值触发后仍需等待成交，实际损失可能超过阈值。</p>
      <details v-if="report.assumptions.length||Object.keys(report.effectiveConfig).length||Object.keys(report.requestConfig).length" class="quant-secondary"><summary>完整参数与成交假设</summary>
        <dl class="quant-detail-meta"><div v-for="item in report.assumptionParameters" :key="item.key"><dt class="muted">{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl>
        <ul v-if="report.assumptions.length" class="quant-research-list"><li v-for="(item,index) in report.assumptions" :key="index">{{ item.text }}</li></ul>
        <p v-else class="muted mt-3">未记录引擎 assumptions。</p>
        <details v-if="report.assumptions.length"><summary>引擎原始假设</summary><pre>{{ JSON.stringify(report.assumptions.map(a=>a.original),null,2) }}</pre></details>
        <details v-if="Object.keys(report.effectiveConfig).length"><summary>当次有效执行配置</summary><pre>{{ JSON.stringify(report.effectiveConfig,null,2) }}</pre></details>
        <details v-if="Object.keys(report.requestConfig).length"><summary>冻结请求配置（不等同于有效执行参数）</summary><pre>{{ JSON.stringify(report.requestConfig,null,2) }}</pre></details>
      </details>
    </section>

    <section class="quant-section quant-research-section">
      <h3>数据来源 / 可复现信息</h3>
      <dl class="quant-detail-meta"><div><dt class="muted">引擎记录的评估区间</dt><dd>{{ range(report.evaluationRange) }}</dd></div><div><dt class="muted">基准</dt><dd>{{ report.benchmark.name || report.benchmark.type || '未记录' }}</dd></div></dl>
      <p class="muted mt-3">{{ sources.length?sources.map(s=>`${lineageNames[s.key]}：${s.name||s.objectId||'未记录'} · ${s.version!=null?'v'+s.version:'版本未记录'}`).join('；'):'冻结来源未记录' }}</p>
      <details class="quant-secondary"><summary>版本血缘与数据明细</summary>
        <dl class="quant-detail-meta"><div><dt class="muted">冻结请求区间</dt><dd>{{ range(report.requestedRange) }}</dd></div><div><dt class="muted">权益曲线实际记录范围</dt><dd>{{ range(report.observedRange) }}<span v-if="report.observedRange.observations">（{{ report.observedRange.observations }} 个观察点）</span></dd></div></dl>
        <h4 class="mt-4 font-medium">当次冻结来源</h4>
        <details v-for="source in sources" :key="source.key" class="quant-secondary"><summary>{{ lineageNames[source.key] }} · {{ source.name||source.objectId||'未记录' }} · {{ source.version!=null?'v'+source.version:'版本未记录' }}<span class="muted ml-2">查看冻结版本</span></summary>
          <dl class="quant-detail-meta"><div><dt class="muted">对象 ID</dt><dd>{{ researchValue(source.objectId) }}</dd></div><div><dt class="muted">版本记录 ID</dt><dd>{{ researchValue(source.versionId) }}</dd></div><div><dt class="muted">当前对象状态（非回测时状态）</dt><dd><QuantStatusBadge :status="source.currentStatus" /><span v-if="!source.currentStatus">未记录</span></dd></div></dl>
          <Button v-if="source.key==='strategy'&&source.state==='AVAILABLE'&&source.currentStatus!=='DELETED'" variant="outline" size="sm" class="mt-3" as-child><RouterLink :to="{path:`/quant/strategies/${source.objectId}`,query:{tab:'backtests',version:source.versionId}}">查看该策略版本产生的回测</RouterLink></Button>
          <pre v-if="source.snapshot" class="mt-3">{{ JSON.stringify(source.snapshot,null,2) }}</pre><p v-else class="muted mt-3">冻结快照不可用，详见研究警告。</p>
        </details>
        <p v-if="!report.lineage.factorSet" class="muted mt-3">因子组合：{{ report.provenance.factorSetVersionId?'版本引用见引擎来源，冻结快照未记录':'未关联独立因子组合；可用因子参数见有效配置' }}。</p>
        <dl class="quant-detail-meta mt-4"><div><dt class="muted">模型引用</dt><dd>{{ report.modelRef || (report.modelApplicable?'未记录':'不适用') }}</dd></div><div v-if="report.modelRef||report.modelApplicable"><dt class="muted">模型训练任务 ID</dt><dd>{{ researchValue(report.modelTaskId) }}</dd></div></dl>
        <h4 class="mt-4 mb-2 font-medium">冻结行情快照</h4>
        <p class="muted mb-3">快照可包含预热数据；现有记录未证明交易日完整性。</p>
        <DataTable v-if="sourceRows.length" :rows="sourceRows" /><p v-else class="muted">未记录逐资产数据范围。</p>
        <h4 class="mt-4 mb-2 font-medium">引擎原始来源字段</h4>
        <dl class="quant-detail-meta"><div v-for="item in technicalRows" :key="item.label"><dt class="muted">{{ item.label }}</dt><dd>{{ researchValue(item.value) }}</dd></div></dl>
        <details v-if="Object.keys(report.provenance).length"><summary>全部原始 provenance</summary><pre>{{ JSON.stringify(report.provenance,null,2) }}</pre></details>
      </details>
    </section>

    <section v-if="report.warnings.length" id="backtest-warnings" class="quant-section quant-research-section" aria-label="研究警告">
      <h3>研究警告<span class="muted ml-2">{{ report.warnings.length }} 项</span></h3>
      <ul class="quant-research-list"><li v-for="warning in report.warnings" :key="warning.key"><p>{{ warning.message }}</p><details><summary class="text-xs">查看来源和详细信息</summary><p class="muted">{{ warning.source }} · {{ warning.code }}</p><pre v-if="warning.details">{{ warning.details }}</pre></details></li></ul>
    </section>
  </div>
</template>
