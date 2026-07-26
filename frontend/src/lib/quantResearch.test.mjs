import assert from 'node:assert/strict'
import {
  buildDefaultParameters,
  canPromoteExperiment,
  recommendedPosition,
  validationCheckRows
} from './quantResearch.js'

const schema = {
  fields: [
    { key: 'linearWeight', defaultValue: 0.5 },
    { key: 'estimators', defaultValue: 48 }
  ]
}

assert.deepEqual(buildDefaultParameters(schema), {
  linearWeight: 0.5,
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
