import { parameterDisplayValue, parameterTitle } from './parameterPresentation.js'

const classificationLabels = { STABLE: '参数稳定性较高', FRAGILE: '参数较敏感', MIXED: '表现混合', INSUFFICIENT: '证据不足' }
const performanceLabels = { POSITIVE: '整体正收益', NEGATIVE: '整体负收益', MIXED: '正负表现混合', FLAT: '表现接近平坦' }
const evidenceLabels = { HIGH: '高', MEDIUM: '中', LOW: '低', INSUFFICIENT: '不足', INVALID: '无效' }
const evidenceAxisLabels = { PASS: '通过', COMPLETE: '完整', PARTIAL: '部分', WARN: '有提示', FAIL: '失败', INSUFFICIENT: '不足' }
const directionLabels = { INCREASING: '局部上升', DECREASING: '局部下降', FLAT: '局部平坦', MIXED: '局部方向混合' }

const errorMessages = {
  PARAMETER_NOT_APPLICABLE: '当前策略不支持该参数的敏感性检查',
  SYMMETRIC_RANGE_UNAVAILABLE: '当前参数附近无法形成完整的五点测试区间',
  INVALID_BASELINE: '当前参数值无法用于敏感性检查',
  UNKNOWN_PARAMETER: '当前参数不支持敏感性检查',
  INVALID_CONSTRAINTS: '当前参数约束不足以生成测试区间',
  MODEL_CONFIG_MISMATCH: '当前模型配置与来源回测不一致，请重新进行正式回测',
  MODEL_NOT_FOUND: '来源回测使用的模型当前不可用，请重新训练并回测',
  CURRENT_RUNTIME_CONFIG_CHANGED: '当前量化引擎的参数规则已变化，请重新执行正式回测',
  CURRENT_RUNTIME_ASSUMPTIONS_CHANGED: '当前量化引擎的研究假设已变化，请重新执行正式回测',
  CURRENT_RUNTIME_BENCHMARK_CONTRACT_CHANGED: '当前基准研究规则已变化，请重新执行正式回测',
}

const known = value => value !== null && value !== undefined && value !== ''
const list = value => Array.isArray(value) ? value : []
const percent = value => typeof value === 'number' && Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '未记录'

export function applicableSensitivityParameters(catalog, strategyType) {
  if (!strategyType) return []
  return list(catalog?.parameters).filter(item => item?.sensitivity?.enabled === true
    && list(item.sensitivity.strategyTypes).includes(strategyType))
}

export function frozenStrategyType(backtest) {
  return backtest?.result?.provenance?.config?.strategyType || null
}

export function createExperimentRefresh(fetchDetail, currentId, accept) {
  let inFlight = false
  return async function refresh() {
    if (inFlight) return false
    const requestedId = currentId()
    if (!requestedId) return false
    inFlight = true
    try {
      const value = await fetchDetail(requestedId)
      if (requestedId !== currentId()) return false
      accept(value)
      return true
    } finally {
      inFlight = false
    }
  }
}

export function explainExperimentError(error) {
  const body = error?.response?.data || {}
  const data = body?.data && typeof body.data === 'object' ? body.data : {}
  const reasonCode = data.reasonCode || data.errorCode || null
  return {
    errorCode: data.errorCode || null,
    reasonCode,
    message: errorMessages[reasonCode] || errorMessages[data.errorCode] || body.message || error?.message || '操作失败，请重试',
  }
}

export function explainExperiment(detail = {}) {
  const summary = detail.summary || {}
  const evidence = detail.evidence || {}
  const provenance = detail.provenance || {}
  const version = provenance.stabilityAlgorithmVersion
  const isV1 = version === 'parameter-stability-v1'
  const isV2 = version === 'parameter-stability-v2'
  const directionConsistencyText = isV1
    ? (known(summary.directionConsistency) ? String(summary.directionConsistency) : '未记录')
    : isV2 ? percent(summary.directionConsistency) : '无法解释'
  const algorithmNotice = isV1
    ? '该实验保留第一版方向一致性的历史含义，不转换为新版百分比。'
    : isV2 ? '方向一致性只描述当前稳定区间内相邻候选点的局部表现，不是未来收益趋势预测。'
      : '该实验使用旧版或未知判断算法，部分稳定性字段无法解释。'
  return {
    ...detail,
    parameterTitle: parameterTitle(detail.parameter?.key),
    baselineText: parameterDisplayValue(detail.parameter?.key, detail.parameter?.baseline),
    classificationText: classificationLabels[summary.classification] || '暂无法判断',
    performanceProfileText: performanceLabels[summary.performanceProfile] || '未记录',
    evidenceQualityText: evidenceLabels[evidence.quality] || '未记录',
    directionText: directionLabels[summary.direction] || (known(summary.direction) ? String(summary.direction) : '未记录'),
    directionConsistencyText,
    algorithmNotice,
    conclusionNotice: summary.classification === 'STABLE' && summary.performanceProfile === 'NEGATIVE'
      ? '参数行为较稳定，但本次纳入计算的候选结果整体为负收益。' : '',
    qualificationNotice: '该验证仅表示满足当前模拟运行准入检查，不代表未来盈利能力，也不要求回测必须盈利或跑赢基准。',
    evidenceAxisText: value => evidenceAxisLabels[value] || value || '未记录',
  }
}
