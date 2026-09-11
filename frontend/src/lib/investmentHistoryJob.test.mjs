import assert from 'node:assert/strict'
import test from 'node:test'
import { createHistoryJobPollingController, shouldPollHistoryJob } from './investmentHistoryJob.js'

function createScheduler() {
  let nextId = 1
  const timers = new Map()
  return {
    timers,
    setTimeout(callback, delay) {
      const id = nextId++
      timers.set(id, { callback, delay })
      return id
    },
    clearTimeout(id) { timers.delete(id) },
    run(id) {
      const timer = timers.get(id)
      timers.delete(id)
      timer.callback()
    },
    firstTimer() { return timers.entries().next().value }
  }
}

function createVisibility() {
  return {
    hidden: false,
    listener: null,
    addEventListener(event, listener) { assert.equal(event, 'visibilitychange'); this.listener = listener },
    removeEventListener(event, listener) { assert.equal(event, 'visibilitychange'); assert.equal(listener, this.listener); this.listener = null }
  }
}

async function flush() {
  await Promise.resolve()
  await Promise.resolve()
}

test('polls active jobs and jobs awaiting automatic recovery', () => {
  for (const status of ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'PARTIAL', 'FAILED']) {
    assert.equal(shouldPollHistoryJob(status), true)
  }

  for (const status of ['SUCCEEDED', undefined, null]) {
    assert.equal(shouldPollHistoryJob(status), false)
  }
})

test('polls every 3 seconds without overlapping requests', async () => {
  const scheduler = createScheduler()
  const pending = []
  const calls = []
  const controller = createHistoryJobPollingController({
    scheduler,
    visibilitySource: createVisibility(),
    poll: context => { calls.push(context); return new Promise(resolve => pending.push(resolve)) }
  })

  controller.update('asset-1', 'RUNNING')
  let [timerId, timer] = scheduler.firstTimer()
  assert.equal(timer.delay, 3000)
  scheduler.run(timerId)
  assert.equal(calls.length, 1)
  controller.update('asset-1', 'RUNNING')
  assert.equal(scheduler.timers.size, 0)

  pending.shift()({ status: 'RUNNING' })
  await flush()
  ;[timerId, timer] = scheduler.firstTimer()
  assert.equal(timer.delay, 3000)
  assert.equal(calls[0].assetId, 'asset-1')
  controller.dispose()
})

test('stops on success and checks partial or failed jobs at the recovery interval', async () => {
  const scheduler = createScheduler()
  const terminals = []
  const jobs = [{ status: 'SUCCEEDED' }, { status: 'PARTIAL' }, { status: 'FAILED' }]
  const controller = createHistoryJobPollingController({
    scheduler,
    visibilitySource: createVisibility(),
    poll: () => Promise.resolve(jobs.shift()),
    onTerminal: (job, context) => terminals.push({ status: job.status, assetId: context.assetId })
  })

  for (const assetId of ['asset-success', 'asset-partial', 'asset-failed']) {
    controller.update(assetId, 'RUNNING')
    const [timerId] = scheduler.firstTimer()
    scheduler.run(timerId)
    await flush()
    assert.equal(scheduler.timers.size, assetId === 'asset-success' ? 0 : 1)
    if (assetId !== 'asset-success') assert.equal(scheduler.firstTimer()[1].delay, 60000)
  }

  assert.deepEqual(terminals, [
    { status: 'SUCCEEDED', assetId: 'asset-success' },
    { status: 'PARTIAL', assetId: 'asset-partial' }
  ])
  controller.dispose()
})

test('pauses when hidden and continues when visible again', () => {
  const scheduler = createScheduler()
  const visibility = createVisibility()
  const controller = createHistoryJobPollingController({ scheduler, visibilitySource: visibility, poll: () => Promise.resolve({ status: 'RUNNING' }) })

  controller.update('asset-1', 'QUEUED')
  const [firstTimerId] = scheduler.firstTimer()
  visibility.hidden = true
  visibility.listener()
  assert.equal(scheduler.timers.size, 0)

  visibility.hidden = false
  visibility.listener()
  const [secondTimerId, timer] = scheduler.firstTimer()
  assert.notEqual(secondTimerId, firstTimerId)
  assert.equal(timer.delay, 3000)
  controller.dispose()
})

test('does not write or rearm after dispose while a request is in flight', async () => {
  const scheduler = createScheduler()
  let resolvePoll
  const results = []
  const controller = createHistoryJobPollingController({
    scheduler,
    visibilitySource: createVisibility(),
    poll: () => new Promise(resolve => { resolvePoll = resolve }),
    onJob: job => results.push(job)
  })

  controller.update('asset-1', 'RUNNING')
  const [timerId] = scheduler.firstTimer()
  scheduler.run(timerId)
  controller.dispose()
  resolvePoll({ status: 'RUNNING' })
  await flush()

  assert.deepEqual(results, [])
  assert.equal(scheduler.timers.size, 0)
})

test('drops an old asset response and resumes polling the current generation', async () => {
  const scheduler = createScheduler()
  let resolveFirst
  const results = []
  const controller = createHistoryJobPollingController({
    scheduler,
    visibilitySource: createVisibility(),
    poll: ({ assetId }) => assetId === 'asset-1'
      ? new Promise(resolve => { resolveFirst = resolve })
      : Promise.resolve({ status: 'RUNNING' }),
    onJob: (job, context) => results.push({ status: job.status, assetId: context.assetId })
  })

  controller.update('asset-1', 'RUNNING')
  let [timerId] = scheduler.firstTimer()
  scheduler.run(timerId)
  controller.update('asset-2', 'RUNNING')
  resolveFirst({ status: 'SUCCEEDED' })
  await flush()

  assert.deepEqual(results, [])
  ;[timerId] = scheduler.firstTimer()
  scheduler.run(timerId)
  await flush()
  assert.deepEqual(results, [{ status: 'RUNNING', assetId: 'asset-2' }])
  controller.dispose()
})

test('restart invalidates an in-flight poll for the same asset before a manual retry', async () => {
  for (const oldStatus of ['SUCCEEDED', 'FAILED']) {
    const scheduler = createScheduler()
    let resolveFirst
    let calls = 0
    const results = []
    const terminals = []
    const controller = createHistoryJobPollingController({
      scheduler,
      visibilitySource: createVisibility(),
      poll: () => {
        calls += 1
        return calls === 1
          ? new Promise(resolve => { resolveFirst = resolve })
          : Promise.resolve({ status: 'RUNNING' })
      },
      onJob: job => results.push(job.status),
      onTerminal: job => terminals.push(job.status)
    })

    controller.update('asset-1', 'RUNNING')
    let [timerId] = scheduler.firstTimer()
    scheduler.run(timerId)
    controller.restart('asset-1', undefined)
    controller.update('asset-1', 'RUNNING')
    resolveFirst({ status: oldStatus })
    await flush()

    assert.deepEqual(results, [])
    assert.deepEqual(terminals, [])
    ;[timerId] = scheduler.firstTimer()
    scheduler.run(timerId)
    await flush()
    assert.deepEqual(results, ['RUNNING'])
    assert.equal(scheduler.timers.size, 1)
    controller.dispose()
  }
})
