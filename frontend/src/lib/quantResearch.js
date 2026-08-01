const TRADABLE_LIFECYCLES = new Set(['VALIDATED', 'PAPER_VERIFIED'])

export function buildDefaultParameters(schema) {
  return Object.fromEntries(
    (schema?.fields || []).map(field => [field.key, field.defaultValue])
  )
}

export function recommendedPosition(analysis) {
  const tradable = TRADABLE_LIFECYCLES.has(analysis?.modelLifecycle)
  return {
    currentWeight: analysis?.currentWeight ?? null,
    recommendedTargetWeight: tradable
      ? (analysis?.recommendedTargetWeight ?? analysis?.targetWeight ?? null)
      : null,
    tradable
  }
}

export function canPromoteExperiment(experiment) {
  return ['SUCCEEDED', 'COMPLETED'].includes(experiment?.status || experiment?.executionStatus)
    && (!experiment?.trainingOutcome || experiment.trainingOutcome === 'VALIDATED')
    && experiment?.validationReport?.passed === true
    && experiment?.validationReport?.lifecycle === 'VALIDATED'
}

export function experimentStatusLabel(experiment) {
  if (!experiment) return '尚未开始'
  const execution = experiment.executionStatus || experiment.status
  const outcome = experiment.trainingOutcome
  if (execution === 'FAILED') return '执行失败'
  if (execution === 'CANCELLED') return '已取消'
  if (outcome === 'DATA_BLOCKED') return '数据未准备完整'
  if (outcome === 'VALIDATION_FAILED') return '执行完成、验证未通过'
  if (outcome === 'VALIDATED') return '执行完成、验证通过'
  if (outcome === 'OPTIMIZING' || execution === 'RUNNING') return '正在优化'
  if (execution === 'QUEUED') return '排队等待'
  if (execution === 'COMPLETED' || execution === 'SUCCEEDED') return '执行完成'
  return '尚未开始'
}

export function formatComparison(comparison) {
  if (!comparison?.comparable) {
    return {
      parameterChange: '无同条件前序实验',
      netExcessChange: '-',
      drawdownImprovement: '-',
      bottleneck: '-',
      nextSuggestion: '-',
    }
  }
  const changes = comparison.parameterChanges || []
  const parameterChange = changes.length
    ? changes.slice(0, 2)
      .map(item => `${item.parameter}：${displayValue(item.before)} → ${displayValue(item.after)}`)
      .join('；') + (changes.length > 2 ? `；另 ${changes.length - 2} 项` : '')
    : '参数未变化'
  return {
    parameterChange,
    netExcessChange: signedPercent(comparison.netExcessChange),
    drawdownImprovement: signedPercent(comparison.drawdownImprovement),
    bottleneck: comparison.bottleneck?.message || comparison.bottleneck?.code || '-',
    nextSuggestion: comparison.nextSuggestion || '-',
  }
}

export function validationCheckRows(report) {
  return (report?.checks || []).map(check => ({
    ...check,
    statusLabel: check.passed ? '通过' : '未通过',
    explanation: check.explanation || ''
  }))
}

export function isExperimentTerminal(status) {
  const value = typeof status === 'object'
    ? (status?.executionStatus || status?.status)
    : status
  return ['SUCCEEDED', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(value)
}

function signedPercent(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  return `${number >= 0 ? '+' : ''}${(number * 100).toFixed(2)}%`
}

function displayValue(value) {
  return value == null ? '未设置' : String(value)
}
