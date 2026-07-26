import assert from 'node:assert/strict'
import test from 'node:test'

import {
  actionLabel,
  formatModelStatus,
  formatTrainingStatus,
  modelCanBeRestored,
  visiblePredictionMetrics,
} from './quantModelManagement.js'

test('普通模式使用用户能理解的模型和训练状态', () => {
  assert.equal(formatModelStatus(null), '暂无有效模型')
  assert.equal(formatModelStatus({ modelLifecycle: 'VALIDATED' }), '模拟观察中')
  assert.equal(formatModelStatus({ modelLifecycle: 'PAPER_VERIFIED' }), '已通过模拟验证')
  assert.equal(formatTrainingStatus({ status: 'RUNNING' }), '正在自动训练和验证')
  assert.equal(formatTrainingStatus({ status: 'SUCCEEDED', errorCode: 'MODEL_REJECTED' }), '本次训练没有找到合格模型')
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

test('仅通过模拟验证的历史模型允许恢复', () => {
  assert.equal(modelCanBeRestored({ modelLifecycle: 'VALIDATED' }), false)
  assert.equal(modelCanBeRestored({ modelLifecycle: 'PAPER_VERIFIED' }), true)
})
