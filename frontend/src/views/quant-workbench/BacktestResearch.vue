<script setup>
import { computed } from 'vue'
import { userMessage } from './presentation.js'
const props = defineProps({ report: { type: Object, required: true }, summary: Boolean })
const issues = computed(() => [...new Set([
  ...props.report.reasons.map(r => userMessage(r.message, r.code, '部分模拟条件尚未满足，请检查配置或数据。')),
  ...props.report.warnings.filter(w => w.source !== 'assumptions').map(w => userMessage(w.message, w.code, '部分研究信息未能核对，请检查数据或重新运行。')),
])])
const sources = computed(() => Object.entries(props.report.lineage).map(([key,item])=>[key,{...item,name:item.name||item.snapshot?.name}]).filter(([,item])=>item.name))
const sourceNames = { strategy: '策略', universe: '资产池', factorSet: '因子组合' }
</script>
<template>
  <section v-if="summary&&(report.status==='UNQUALIFIED'||issues.length)" class="quant-section" aria-label="需要注意">
    <h3>{{ report.status==='UNQUALIFIED'?'暂不能继续模拟':'需要注意' }}</h3>
    <ul v-if="issues.length" class="quant-research-list"><li v-for="message in issues" :key="message">{{ message }}</li></ul>
    <p v-else class="muted">本次结果未满足模拟运行条件，未记录具体原因。</p>
  </section>
  <template v-else-if="!summary">
    <details v-if="report.assumptionParameters.length||sources.length||report.dataSnapshot.length" class="panel">
      <summary>本次设置与数据</summary>
      <dl v-if="report.assumptionParameters.length" class="quant-detail-meta">
        <div v-for="item in report.assumptionParameters" :key="item.key"><dt class="muted">{{ item.label }}</dt><dd>{{ item.value }}</dd></div>
      </dl>
      <p v-if="report.assumptionParameters.some(p=>['feeRate','sellFeeRate','slippageBps'].includes(p.key))" class="muted mt-3">费用与成交条件使用本次模拟设置。</p>
      <dl v-if="sources.length" class="quant-detail-meta mt-4"><div v-for="[key,item] in sources" :key="key"><dt class="muted">{{ sourceNames[key] }}</dt><dd>{{ item.name }}<span v-if="item.version!=null"> · v{{ item.version }}</span></dd></div></dl>
      <ul v-if="report.dataSnapshot.length" class="quant-research-list"><li v-for="(asset,index) in report.dataSnapshot" :key="index">{{ asset.name||'资产' }} · {{ asset.startDate||'未记录' }} ～ {{ asset.endDate||'未记录' }} · {{ asset.observations??'未知' }} 条记录</li></ul>
    </details>
  </template>
</template>
