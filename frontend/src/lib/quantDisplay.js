const USER_VISIBLE_LIFECYCLES = new Set(['VALIDATED', 'PAPER_VERIFIED'])
const DEPLOYED_STATUSES = new Set(['PAPER', 'CHAMPION', 'CHALLENGER'])

export function isUsableQuantAnalysis(analysis) {
  return analysis?.status === 'READY'
    && USER_VISIBLE_LIFECYCLES.has(String(analysis.modelLifecycle || '').toUpperCase())
    && DEPLOYED_STATUSES.has(String(analysis.deploymentStatus || '').toUpperCase())
}

export function quantSummaryMetric(analysis, formatProbability = value => String(value ?? '-')) {
  if (!isUsableQuantAnalysis(analysis)) {
    return {
      label: '量化模型',
      value: '暂无有效模型',
      hint: '等待自动训练',
    }
  }
  return {
    label: '量化跑赢概率',
    value: formatProbability(analysis.probabilityPositiveExcess),
    hint: analysis.action || 'HOLD',
  }
}

export function trainingCompletionMessage(job) {
  if (job?.status !== 'SUCCEEDED') return ''
  const lifecycle = String(
    job?.result?.modelStatus
      || job?.result?.validationReport?.lifecycle
      || '',
  ).toUpperCase()
  return USER_VISIBLE_LIFECYCLES.has(lifecycle)
    ? '合格模型已保存并进入模拟验证'
    : '训练任务已完成，但本次没有产生合格模型'
}
