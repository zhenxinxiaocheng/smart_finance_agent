import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isUsableQuantAnalysis,
  quantSummaryMetric,
  trainingCompletionMessage,
} from './quantDisplay.js'

test('普通页面只展示已验证且已部署的量化结果', () => {
  assert.equal(isUsableQuantAnalysis({
    status: 'READY',
    modelLifecycle: 'DRAFT',
    probabilityPositiveExcess: 1,
  }), false)
  assert.equal(isUsableQuantAnalysis({
    status: 'READY',
    modelLifecycle: 'VALIDATED',
    deploymentStatus: 'CHALLENGER',
  }), true)
  assert.equal(isUsableQuantAnalysis({
    status: 'READY',
    modelLifecycle: 'PAPER_VERIFIED',
    deploymentStatus: 'CHAMPION',
  }), true)
})

test('无有效模型时摘要不泄漏候选概率', () => {
  assert.deepEqual(
    quantSummaryMetric({
      status: 'READY',
      modelLifecycle: 'DRAFT',
      probabilityPositiveExcess: 0.894,
    }),
    {
      label: '量化模型',
      value: '暂无有效模型',
      hint: '等待自动训练',
    },
  )
})

test('有效模型摘要显示未来盈利概率而不是跑赢基准概率', () => {
  assert.deepEqual(
    quantSummaryMetric({
      status: 'READY',
      modelLifecycle: 'PAPER_VERIFIED',
      deploymentStatus: 'CHAMPION',
      profitProbability: 0.64,
      probabilityPositiveExcess: 0.89,
      action: 'HOLD',
    }, value => `${value * 100}%`),
    {
      label: '未来盈利概率',
      value: '64%',
      hint: 'HOLD',
    },
  )
})

test('任务完成与模型验证通过使用不同提示', () => {
  assert.equal(trainingCompletionMessage({
    status: 'SUCCEEDED',
    result: { modelStatus: 'DRAFT' },
  }), '训练任务已完成，但本次没有产生合格模型')
  assert.equal(trainingCompletionMessage({
    status: 'SUCCEEDED',
    result: { modelStatus: 'VALIDATED' },
  }), '合格模型已保存并进入模拟验证')
})
