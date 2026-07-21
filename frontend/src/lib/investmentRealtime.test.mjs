import test from 'node:test'
import assert from 'node:assert/strict'
import { formatQuoteTime, startInvestmentRealtimePolling } from './investmentRealtime.js'

test('uses a 3000ms polling interval and returns cleanup', () => {
  const scheduler = {
    interval: null,
    cleared: false,
    setInterval(_callback, interval) { this.interval = interval; return 17 },
    clearInterval(id) { this.cleared = id === 17 }
  }

  const stop = startInvestmentRealtimePolling(() => {}, 3000, scheduler)

  assert.equal(scheduler.interval, 3000)
  stop()
  assert.equal(scheduler.cleared, true)
})

test('shows exact quote time for stocks and data date for funds', () => {
  assert.equal(formatQuoteTime({ productType: 'STOCK', fetchedAt: '2026-07-13T11:23:30' }), '11:23:30')
  assert.equal(formatQuoteTime({ productType: 'MUTUAL_FUND', dataDate: '2026-07-12' }), '2026-07-12')
})
