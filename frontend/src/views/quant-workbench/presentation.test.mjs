import assert from 'node:assert/strict'
import test from 'node:test'
import { userMessage, needsTraining } from './presentation.js'
test('only supported ML strategies expose training', () => {
  for (const type of ['TREND', 'MULTI_FACTOR', 'ML_FUTURE', undefined]) assert.equal(needsTraining(type), false)
  for (const type of ['ML_ELASTIC_NET', 'ML_XGBOOST']) assert.equal(needsTraining(type), true)
})
test('blocking messages survive while exceptions and internal codes do not', () => {
  assert.match(userMessage('有效评估日期不足，至少需要60日'), /60日/)
  assert.match(userMessage('asset-a: no observations', 'INSUFFICIENT_DATA'), /历史数据不足/)
  for (const message of ['Traceback: /app/engine.py', 'java.lang.IllegalStateException: 配置错误', 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION', '错误 MODEL_LOOKAHEAD']) {
    assert.equal(userMessage(message), '操作未完成，请稍后重试。')
  }
})
