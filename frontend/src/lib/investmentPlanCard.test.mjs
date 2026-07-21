import assert from 'node:assert/strict'
import test from 'node:test'

const helper = await import('./investmentPlanCard.js').catch(() => null)

test('定投卡片计算用户当前实际投入', { skip: !helper }, () => {
  assert.equal(helper.actualInvestedAmount({ quantity: '1450.74', averageCost: '1.289' }), 1870.00386)
  assert.equal(helper.actualInvestedAmount({ quantity: null, averageCost: '1.289' }), null)
})

test('定投卡片使用当前基金构造已有接口需要的请求', { skip: !helper }, () => {
  const payload = helper.buildInvestmentPlanPayload({
    accountId: 3,
    productType: 'MUTUAL_FUND',
    market: 'FUND_CN',
    code: '010736',
    name: '易方达沪深300指数增强A',
    currency: 'CNY'
  }, {
    amount: '500',
    frequency: 'MONTHLY',
    executionDay: 15
  }, new Date('2026-07-20T12:00:00'))

  assert.deepEqual(payload, {
    accountId: 3,
    product: {
      productType: 'MUTUAL_FUND',
      market: 'FUND_CN',
      code: '010736',
      name: '易方达沪深300指数增强A',
      currency: 'CNY'
    },
    amount: 500,
    currency: 'CNY',
    frequency: 'MONTHLY',
    executionDay: 15,
    nextExecutionDate: '2026-08-15'
  })
})

test('每日定投固定执行日并从当天开始', { skip: !helper }, () => {
  const payload = helper.buildInvestmentPlanPayload({
    accountId: 3,
    productType: 'MUTUAL_FUND',
    market: 'FUND_CN',
    code: '010736',
    name: '易方达沪深300指数增强A',
    currency: 'CNY'
  }, {
    amount: '10',
    frequency: 'DAILY',
    executionDay: 18
  }, new Date('2026-07-13T12:00:00'))

  assert.equal(payload.frequency, 'DAILY')
  assert.equal(payload.executionDay, 1)
  assert.equal(payload.nextExecutionDate, '2026-07-13')
})

test('定投卡片逻辑模块存在', () => {
  assert.ok(helper, 'investmentPlanCard.js 尚未实现')
})
