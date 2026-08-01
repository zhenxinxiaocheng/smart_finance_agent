import assert from 'node:assert/strict'
import test from 'node:test'

import {
  actionLabel,
  formatModelStatus,
  formatTrainingStatus,
  modelCanBeRestored,
  shouldShowExecutionPlan,
  visiblePredictionMetrics,
} from './quantModelManagement.js'

test('普通模式使用用户能理解的模型和训练状态', () => {
  assert.equal(formatModelStatus(null), '暂无有效模型')
  assert.equal(formatModelStatus({ modelLifecycle: 'VALIDATED' }), '模拟观察中')
  assert.equal(formatModelStatus({ modelLifecycle: 'PAPER_VERIFIED' }), '已通过模拟验证')
  assert.equal(formatTrainingStatus({ status: 'RUNNING' }), '正在自动训练和验证')
  assert.equal(formatTrainingStatus({
    status: 'SUCCEEDED',
    executionStatus: 'COMPLETED',
    trainingOutcome: 'VALIDATION_FAILED',
  }), '执行完成、验证未通过；已保留风险参考')
  assert.equal(formatTrainingStatus({
    executionStatus: 'COMPLETED',
    trainingOutcome: 'VALIDATED',
  }), '执行完成，模型已通过经济验证')
})

test('操作名称直接表达用户应该做什么', () => {
  assert.equal(actionLabel('BUY'), '买入')
  assert.equal(actionLabel('REDUCE'), '减仓')
  assert.equal(actionLabel('PAUSE'), '暂停操作')
})

test('无有效操作计划时不显示预测数字', () => {
  assert.deepEqual(visiblePredictionMetrics({
    status: 'PAUSED',
    profitProbability: 0.89,
    expectedNetReturn: 0.12,
  }), [])
})

test('暂停状态或风险参考状态不显示零值执行计划', () => {
  assert.equal(shouldShowExecutionPlan({ status: 'PAUSED' }, null), false)
  assert.equal(shouldShowExecutionPlan({ status: 'READY' }, { economicRole: 'RISK_REFERENCE' }), false)
  assert.equal(shouldShowExecutionPlan({ status: 'READY' }, null), true)
})

test('仅通过模拟验证的历史模型允许恢复', () => {
  assert.equal(modelCanBeRestored({ modelLifecycle: 'VALIDATED' }), false)
  assert.equal(modelCanBeRestored({ modelLifecycle: 'PAPER_VERIFIED' }), true)
})
