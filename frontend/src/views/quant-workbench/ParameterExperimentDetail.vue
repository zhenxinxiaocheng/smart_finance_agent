<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import QuantPageHeader from './QuantPageHeader.vue'
import QuantStatusBadge from './QuantStatusBadge.vue'
import ParameterExperimentReport from './ParameterExperimentReport.vue'
import { quant } from '@/api/quantWorkbench.js'
import { activeTask, formatTime, usePoll } from './shared.js'
import { createExperimentPolling } from './experimentPolling.js'
import { parameterTitle } from './parameterPresentation.js'

const route = useRoute()
const detail = ref(null)
const loading = ref(true)
const loadError = ref(null)
const refreshWarning = ref(false)

const currentId = () => String(route.params.id || '')
const polling = createExperimentPolling(
  (id, options) => quant.experiments.get(id, options),
  state => {
    detail.value = state.detail
    loading.value = state.loading
    loadError.value = state.error
    refreshWarning.value = state.warning
  },
)
const load = () => polling.load(currentId())
onMounted(load)
watch(() => route.params.id, load, { flush: 'sync' })
onBeforeUnmount(polling.dispose)
usePoll(polling.poll, polling.shouldPoll)
</script>

<template>
  <p v-if="loading&&!detail" class="muted">正在加载参数敏感性检查…</p>
  <p v-else-if="loadError&&!detail" class="panel text-sm text-destructive" role="alert">{{ loadError.message }}</p>
  <template v-if="detail">
    <QuantPageHeader :title="detail.name||'参数敏感性检查'">
      <template #back><Button variant="ghost" size="sm" as-child><RouterLink :to="{path:'/quant/tasks',query:{type:'backtests',id:detail.sourceBacktest?.id||detail.sourceBacktestId}}">返回来源回测</RouterLink></Button></template>
      <template #badges><QuantStatusBadge :status="detail.status" /><Badge variant="outline" class="quant-status">{{ parameterTitle(detail.parameter?.key) }}</Badge></template>
      <template #meta><span>{{ detail.strategy?.name||'冻结策略名称未记录' }}<template v-if="detail.strategy?.version!=null"> · v{{ detail.strategy.version }}</template></span><span>{{ detail.researchWindow?.startDate||'未记录' }} ～ {{ detail.researchWindow?.endDate||'未记录' }}</span><span>更新于 {{ formatTime(detail.updatedAt) }}</span></template>
      <template #actions><Button variant="outline" as-child><RouterLink :to="{path:'/quant/tasks',query:{type:'backtests',id:detail.sourceBacktest?.id||detail.sourceBacktestId}}">查看来源回测</RouterLink></Button></template>
    </QuantPageHeader>
    <p v-if="refreshWarning" class="muted mb-3">自动刷新暂时失败，正在继续尝试。</p>
    <p v-if="loadError" class="mb-3 text-sm text-destructive" role="alert">{{ loadError.message }}</p>
    <div v-if="activeTask(detail.status)" class="panel"><h3>检查进度</h3><progress v-if="typeof detail.progress==='number'" :value="detail.progress" max="100" aria-label="实际实验进度"></progress><p v-else class="muted">当前阶段未报告百分比进度。</p></div>
    <p v-if="detail.status==='PARTIAL'" class="quant-validation">部分候选运行未完成，以下结论仅基于后端纳入计算的结果。</p>
    <p v-if="detail.status==='FAILED'" class="quant-validation text-destructive">参数敏感性检查未完成；已产生的候选结果仍保留供核对。</p>
    <ParameterExperimentReport :detail="detail" />
  </template>
</template>
