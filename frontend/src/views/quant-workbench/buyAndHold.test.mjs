import assert from 'node:assert/strict'
import test from 'node:test'
import { buyAndHoldComparison } from './buyAndHold.js'

test('relative return preserves positive, negative and zero differences', () => {
  for (const [strategy, baseline, expected] of [[.28, .2, '+8.00%'], [.1, .25, '-15.00%'], [.2, .2, '0.00%']]) {
    const report = buyAndHoldComparison({ metrics: { netReturn: strategy, maxDrawdown: .1 }, buyAndHold: {
      status: 'READY', metrics: { netReturn: baseline, maxDrawdown: .2, fees: 0 },
    } })
    assert.equal(report.items.find(i => i.key === 'relativeReturn').value, expected)
    assert.equal(report.items.find(i => i.key === 'holdDrawdown').value, '20.00%')
    assert.equal(report.items.find(i => i.key === 'holdFees').value, '0')
  }
})

test('missing and unavailable baselines never show a fabricated comparison', () => {
  assert.equal(buyAndHoldComparison({}), null)
  const report = buyAndHoldComparison({ buyAndHold: { status: 'UNAVAILABLE', reason: 'BUY_AND_HOLD_ASSETS_NOT_FILLED' } })
  assert.deepEqual(report.items, [])
  assert.match(report.message, /未能完成买入/)
  const incomplete = buyAndHoldComparison({ metrics: {}, buyAndHold: { status: 'READY', metrics: {} } })
  assert.equal(incomplete.items.find(i => i.key === 'relativeReturn').value, '—')
})
