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
  return experiment?.status === 'SUCCEEDED'
    && experiment?.validationReport?.passed === true
    && experiment?.validationReport?.lifecycle === 'VALIDATED'
}

export function validationCheckRows(report) {
  return (report?.checks || []).map(check => ({
    ...check,
    statusLabel: check.passed ? '通过' : '未通过',
    explanation: check.explanation || ''
  }))
}

export function isExperimentTerminal(status) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(status)
}
