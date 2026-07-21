import assert from 'node:assert/strict'
import test from 'node:test'

import { buildQuantityReferenceState, missingPriceZoneText } from './investmentActionState.js'

test('有有效买入预算时展示批次和总预算', () => {
  const state = buildQuantityReferenceState({
    productType: 'STOCK',
    suggestedBudget: 30000,
    sellQuantity: 0,
    technicalConfidence: 0.6,
    batches: [{ amount: 10000, quantity: 100 }]
  })

  assert.equal(state.showBatches, true)
  assert.equal(state.showBudget, true)
  assert.equal(state.emptyMessage, '')
})

test('预算不足一手时不展示三个零批次', () => {
  const state = buildQuantityReferenceState({
    productType: 'STOCK',
    suggestedBudget: 1200,
    sellQuantity: 0,
    technicalConfidence: 0.4,
    batches: [{ amount: 400, quantity: 0 }, { amount: 400, quantity: 0 }, { amount: 400, quantity: 0 }]
  })

  assert.equal(state.showBatches, false)
  assert.match(state.emptyMessage, /不足 100 股/)
})

test('没有买卖信号时给出中性观察说明', () => {
  const state = buildQuantityReferenceState({
    productType: 'STOCK',
    suggestedBudget: 0,
    sellQuantity: 0,
    technicalConfidence: 0,
    bearishConfidence: 0,
    batches: []
  })

  assert.match(state.emptyMessage, /没有形成明确的买入或减仓信号/)
})

test('缺失价位按原因显示而不是短横线', () => {
  assert.equal(missingPriceZoneText({ hasLatestPrice: false, historyInsufficient: false }), '缺少最新价格')
  assert.equal(missingPriceZoneText({ hasLatestPrice: true, historyInsufficient: true }), '历史数据不足')
  assert.equal(missingPriceZoneText({ hasLatestPrice: true, historyInsufficient: false }), '当前未形成有效区间')
})
