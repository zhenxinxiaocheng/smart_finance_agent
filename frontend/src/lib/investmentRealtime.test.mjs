import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createPrioritizedRefreshRunner,
  formatQuoteTime,
  startInvestmentRealtimePolling
} from './investmentRealtime.js'

test('uses a 3000ms polling interval and returns cleanup', () => {
  const scheduler = {
    interval: null,
    cleared: false,
    setInterval(callback, interval) { this.callback = callback; this.interval = interval; return 17 },
    clearInterval(id) { this.cleared = id === 17 }
  }
  const calls = []

  const stop = startInvestmentRealtimePolling(force => calls.push(force), 3000, scheduler)

  assert.equal(scheduler.interval, 3000)
  scheduler.callback()
  assert.deepEqual(calls, [false])
  stop()
  assert.equal(scheduler.cleared, true)
})

test('pauses while hidden and immediately forces a refresh when visible again', () => {
  const timers = []
  const scheduler = {
    cleared: [],
    setInterval(callback, interval) {
      const timer = { id: timers.length + 1, callback, interval }
      timers.push(timer)
      return timer.id
    },
    clearInterval(id) { this.cleared.push(id) }
  }
  const visibility = {
    hidden: false,
    listener: null,
    addEventListener(event, listener) {
      assert.equal(event, 'visibilitychange')
      this.listener = listener
    },
    removeEventListener(event, listener) {
      assert.equal(event, 'visibilitychange')
      assert.equal(listener, this.listener)
      this.listener = null
    }
  }
  const calls = []

  const stop = startInvestmentRealtimePolling(force => calls.push(force), 3000, scheduler, visibility)
  assert.equal(timers.length, 1)

  visibility.hidden = true
  visibility.listener()
  assert.deepEqual(scheduler.cleared, [1])

  visibility.hidden = false
  visibility.listener()
  assert.deepEqual(calls, [true])
  assert.equal(timers.length, 2)
  timers[1].callback()
  assert.deepEqual(calls, [true, false])

  stop()
  assert.deepEqual(scheduler.cleared, [1, 2])
  assert.equal(visibility.listener, null)
})

test('does not start polling while the page is initially hidden', () => {
  const scheduler = {
    started: false,
    setInterval() { this.started = true; return 1 },
    clearInterval() {}
  }
  const visibility = {
    hidden: true,
    addEventListener(_event, listener) { this.listener = listener },
    removeEventListener() {}
  }

  const stop = startInvestmentRealtimePolling(() => {}, 3000, scheduler, visibility)

  assert.equal(scheduler.started, false)
  stop()
})

test('queues a forced refresh behind an in-flight normal refresh instead of dropping it', async () => {
  const resolvers = []
  const calls = []
  const run = createPrioritizedRefreshRunner((force, silent) => {
    calls.push({ force, silent })
    return new Promise(resolve => resolvers.push(resolve))
  })

  const normal = run(false, true)
  const forced = run(true, true)

  assert.deepEqual(calls, [{ force: false, silent: true }])
  resolvers.shift()('normal')
  await normal
  await Promise.resolve()
  assert.deepEqual(calls, [
    { force: false, silent: true },
    { force: true, silent: true }
  ])
  resolvers.shift()('forced')
  assert.equal(await forced, 'forced')
})

test('coalesces pending refreshes and keeps the strongest force and loading intent', async () => {
  const resolvers = []
  const calls = []
  const run = createPrioritizedRefreshRunner((force, silent) => {
    calls.push({ force, silent })
    return new Promise(resolve => resolvers.push(resolve))
  })

  const first = run(false, true)
  const pendingNormal = run(false, true)
  const pendingForcedVisible = run(true, false)
  resolvers.shift()()
  await first
  await Promise.resolve()

  assert.deepEqual(calls[1], { force: true, silent: false })
  resolvers.shift()('latest')
  assert.equal(await pendingNormal, 'latest')
  assert.equal(await pendingForcedVisible, 'latest')
})

test('dispose clears a pending refresh and prevents it from starting after unmount', async () => {
  const resolvers = []
  const calls = []
  const run = createPrioritizedRefreshRunner(force => {
    calls.push(force)
    return new Promise(resolve => resolvers.push(resolve))
  })

  const active = run(false)
  const pending = run(true)
  run.dispose()
  resolvers.shift()('active')

  assert.equal(await active, 'active')
  assert.equal(await pending, undefined)
  assert.deepEqual(calls, [false])
  assert.equal(await run(true), undefined)
})

test('joins duplicate focus and visibility refreshes to the same in-flight forced request', async () => {
  let resolveRequest
  const calls = []
  const run = createPrioritizedRefreshRunner(force => {
    calls.push(force)
    return new Promise(resolve => { resolveRequest = resolve })
  })

  const focus = run(true, true)
  const visibility = run(true, true)

  assert.deepEqual(calls, [true])
  resolveRequest('fresh')
  assert.equal(await focus, 'fresh')
  assert.equal(await visibility, 'fresh')
  assert.deepEqual(calls, [true])
})

test('runs a mutation refresh after an older forced request instead of joining it', async () => {
  const resolvers = []
  const calls = []
  const run = createPrioritizedRefreshRunner(force => {
    calls.push(force)
    return new Promise(resolve => resolvers.push(resolve))
  })

  const focus = run(true, true)
  const mutation = run(true, true, true)
  assert.deepEqual(calls, [true])

  resolvers.shift()('before-mutation')
  await focus
  await Promise.resolve()
  assert.deepEqual(calls, [true, true])

  resolvers.shift()('after-mutation')
  assert.equal(await mutation, 'after-mutation')
})

test('shows exact quote time for stocks and data date for funds', () => {
  assert.equal(formatQuoteTime({ productType: 'STOCK', fetchedAt: '2026-07-13T11:23:30' }), '11:23:30')
  assert.equal(formatQuoteTime({ productType: 'MUTUAL_FUND', dataDate: '2026-07-12' }), '2026-07-12')
})
