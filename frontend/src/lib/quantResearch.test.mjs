import assert from 'node:assert/strict'
import {
  buildDefaultParameters,
  canPromoteExperiment,
  experimentStatusLabel,
  formatComparison,
  recommendedPosition,
  validationCheckRows
} from './quantResearch.js'

const schema = {
  fields: [
    { key: 'linearWeight', defaultValue: 0.5, algorithms: ['REGIME_ENSEMBLE'] },
    { key: 'estimators', defaultValue: 48, algorithms: ['XGBOOST'] }
  ]
}

assert.deepEqual(buildDefaultParameters(schema), {
  linearWeight: 0.5,
  estimators: 48
})
assert.deepEqual(buildDefaultParameters(schema, 'XGBOOST'), {
  estimators: 48
})

assert.deepEqual(recommendedPosition({
  modelLifecycle: 'DRAFT',
  currentWeight: 0.18,
  targetWeight: 0.75
}), {
  currentWeight: 0.18,
  recommendedTargetWeight: null,
  tradable: false
})

assert.deepEqual(recommendedPosition({
  modelLifecycle: 'VALIDATED',
  currentWeight: 0.18,
  targetWeight: 0.25
}), {
  currentWeight: 0.18,
  recommendedTargetWeight: 0.25,
  tradable: true
})

assert.equal(canPromoteExperiment({
  status: 'SUCCEEDED',
  validationReport: { passed: true, lifecycle: 'VALIDATED' }
}), true)
assert.equal(canPromoteExperiment({
  status: 'SUCCEEDED',
  validationReport: { passed: false, lifecycle: 'DRAFT' }
}), false)

assert.equal(experimentStatusLabel({
  executionStatus: 'COMPLETED',
  trainingOutcome: 'VALIDATION_FAILED'
}), '执行完成、验证未通过')
assert.equal(experimentStatusLabel({
  executionStatus: 'RUNNING',
  trainingOutcome: 'OPTIMIZING'
}), '正在优化')

assert.deepEqual(formatComparison({
  comparable: true,
  parameterChanges: [
    { parameter: 'maximumDepth', before: 5, after: 2 }
  ],
  netExcessChange: 0.012,
  drawdownImprovement: 0.07,
  bottleneck: { code: 'COST_TOO_HIGH', message: '交易成本过高' },
  nextSuggestion: '降低换手'
}), {
  parameterChange: 'maximumDepth：5 → 2',
  netExcessChange: '+1.20%',
  drawdownImprovement: '+7.00%',
  bottleneck: '交易成本过高',
  nextSuggestion: '降低换手'
})

assert.deepEqual(validationCheckRows({
  checks: [
    { key: 'oosR2', actual: -0.02, threshold: '> 0', passed: false, explanation: '样本外解释力' }
  ]
}), [{
  key: 'oosR2',
  actual: -0.02,
  threshold: '> 0',
  passed: false,
  statusLabel: '未通过',
  explanation: '样本外解释力'
}])

console.log('quantResearch tests passed')
