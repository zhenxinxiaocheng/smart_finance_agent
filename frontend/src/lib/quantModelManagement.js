const MODEL_STATUS_LABELS = {
  VALIDATED: '模拟观察中',
  PAPER_VERIFIED: '已通过模拟验证',
  RETIRED: '已停止使用',
}

const TRAINING_STATUS_LABELS = {
  NOT_STARTED: '等待首次自动训练',
  QUEUED: '等待自动训练',
  RUNNING: '正在自动训练和验证',
  FAILED: '自动训练执行失败',
  CANCELLED: '自动训练已取消',
}

const ACTION_LABELS = {
  BUY: '买入',
  ADD: '加仓',
  HOLD: '继续持有',
  REDUCE: '减仓',
  EXIT: '卖出',
  PAUSE: '暂停操作',
}

export function formatModelStatus(model) {
  if (!model) return '暂无有效模型'
  return MODEL_STATUS_LABELS[model.modelLifecycle] || '暂无有效模型'
}

export function formatTrainingStatus(training) {
  if (!training) return TRAINING_STATUS_LABELS.NOT_STARTED
  if (training.executionStatus === 'FAILED') return '自动训练执行失败'
  if (training.trainingOutcome === 'DATA_BLOCKED') return '数据未准备完整，暂不启动正式训练'
  if (training.trainingOutcome === 'VALIDATION_FAILED') return '执行完成、验证未通过；已保留风险参考'
  if (training.trainingOutcome === 'VALIDATED') return '执行完成，模型已通过经济验证'
  if (training.errorCode) return '本次训练没有找到合格模型'
  if (training.status === 'SUCCEEDED') return '自动训练已完成'
  return TRAINING_STATUS_LABELS[training.status] || TRAINING_STATUS_LABELS.NOT_STARTED
}

export function actionLabel(action) {
  return ACTION_LABELS[action] || ACTION_LABELS.PAUSE
}

export function visiblePredictionMetrics(plan) {
  if (plan?.status !== 'READY') return []
  return [
    { key: 'profitProbability', label: '未来盈利概率', value: plan.profitProbability, type: 'percent' },
    { key: 'expectedNetReturn', label: '预计扣费后收益', value: plan.expectedNetReturn, type: 'percent' },
    { key: 'lossProbability', label: '未来亏损概率', value: plan.lossProbability, type: 'percent' },
    { key: 'predictionInterval', label: '可能收益区间', value: plan.predictionInterval, type: 'interval' },
  ]
}

export function shouldShowExecutionPlan(plan, technicalSignal) {
  return plan?.status === 'READY' && technicalSignal?.economicRole !== 'RISK_REFERENCE'
}

export function modelCanBeRestored(model) {
  return model?.modelLifecycle === 'PAPER_VERIFIED'
}
