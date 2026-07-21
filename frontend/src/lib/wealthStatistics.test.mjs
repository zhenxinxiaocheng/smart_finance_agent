import assert from 'node:assert/strict'
import test from 'node:test'
import { buildWealthStatistics } from './wealthStatistics.js'

test('总资产模型直接使用后端财富汇总并展示资产构成', () => {
  const model = buildWealthStatistics({
    initialized: true,
    totalAssets: 102000,
    dailyCash: 77000,
    investmentTotal: 25000,
    investmentCash: 3000,
    holdingMarketValue: 22000
  }, 12000, 5000, 9)

  assert.equal(model.cards[0].value, 102000)
  assert.equal(model.cards[1].value, 77000)
  assert.equal(model.cards[2].value, 25000)
  assert.equal(model.cards[3].value, 7000)
  assert.equal(model.composition[2].value, 22000)
  assert.equal(model.activity[2].value, 9)
})

test('现金未初始化时不把投资资产冒充总资产', () => {
  const model = buildWealthStatistics({ initialized: false, investmentTotal: 25000 }, 0, 0, 0)

  assert.equal(model.cards[0].value, null)
  assert.equal(model.cards[2].value, 25000)
})
