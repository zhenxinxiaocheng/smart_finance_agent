import assert from 'node:assert/strict'
import test from 'node:test'
import { createExperimentPolling, pollingErrorKind } from './experimentPolling.js'

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const error = status => ({ response: { status, data: { message: '安全提示' } } })

test('polling classifies by status, never message', () => {
  for (const status of [408, 429, 500, 503, 599]) assert.equal(pollingErrorKind(error(status)), 'retry')
  for (const status of [400, 403, 404, 422]) assert.equal(pollingErrorKind(error(status)), 'stop')
  assert.equal(pollingErrorKind(error(401)), 'session')
  assert.equal(pollingErrorKind({ code: 'ECONNABORTED', message: '404' }), 'retry')
  assert.equal(pollingErrorKind(new Error('403')), 'retry')
  assert.equal(pollingErrorKind({ code: 'ERR_CANCELED' }), 'cancel')
  assert.equal(pollingErrorKind({ response: { status: 200, data: { code: 401 } } }), 'session')
  assert.equal(pollingErrorKind({ response: { status: 200, data: { code: 404 } } }), 'stop')
})

test('active states retry, preserve DTO and clear warning; terminal states never request', async () => {
  for (const active of ['QUEUED', 'RUNNING']) {
    let state, calls = 0
    const original = { id: 'a', status: active, revision: 1, progress: null, runs: [{ status: 'QUEUED' }] }
    const next = { ...original, runs: [{ status: 'SUCCEEDED' }] }
    const polling = createExperimentPolling(async () => { calls++; if (calls === 2) throw error(503); return calls === 1 ? original : next }, value => { state = value })
    await polling.load('a')
    await polling.poll()
    assert.equal(state.detail, original)
    assert.equal(state.warning, true)
    assert.equal(state.detail.progress, null)
    await polling.poll()
    assert.equal(state.detail, next)
    assert.equal(state.warning, false)
    assert.equal(calls, 3)
  }
  for (const status of ['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'UNKNOWN']) {
    let calls = 0
    const polling = createExperimentPolling(async () => { calls++; return { id: 'a', status } }, () => {})
    await polling.load('a'); await polling.poll()
    assert.equal(calls, 1)
  }
})

test('fatal errors keep successful data and stop requests; 401 stays in session flow', async () => {
  for (const status of [400, 401, 403, 404]) {
    let state, calls = 0
    const polling = createExperimentPolling(async () => { if (++calls > 1) throw error(status); return { id: 'a', status: 'RUNNING' } }, value => { state = value })
    await polling.load('a'); await polling.poll(); await polling.poll()
    assert.equal(calls, 2)
    assert.equal(state.detail.id, 'a')
    assert.equal(state.error?.message ?? null, status === 401 ? null : '安全提示')
  }
})

test('load and poll share inFlight; stale success/error/finally cannot alter a new route', async () => {
  const pending = [], calls = []
  let state
  const polling = createExperimentPolling((id, options) => { calls.push({ id, options }); const d = deferred(); pending.push(d); return d.promise }, value => { state = value })
  const first = polling.load('a')
  await polling.poll(); await polling.load('a')
  assert.equal(calls.length, 1)
  const second = polling.load('b')
  const third = polling.load('a')
  assert.equal(calls[0].options.signal.aborted, true)
  pending[0].resolve({ id: 'a', status: 'FAILED' }); await first
  pending[1].reject(error(404)); await second
  assert.equal(state.loading, true)
  assert.equal(state.detail, null)
  await polling.poll()
  assert.equal(calls.length, 3)
  pending[2].resolve({ id: 'a', status: 'RUNNING' }); await third
  const refresh = polling.poll()
  await polling.poll()
  assert.equal(calls.length, 4)
  assert.equal(calls[3].options.silent, true)
  pending[3].resolve({ id: 'a', status: 'SUCCEEDED' }); await refresh
  assert.equal(state.detail.status, 'SUCCEEDED')
})

test('dispose aborts pending request and blocks all late state writes', async () => {
  const d = deferred(); let writes = 0, signal
  const polling = createExperimentPolling((id, options) => { signal = options.signal; return d.promise }, () => { writes++ })
  const request = polling.load('a'); polling.dispose()
  const before = writes
  d.resolve({ id: 'a', status: 'RUNNING' }); await request; await polling.poll()
  assert.equal(signal.aborted, true)
  assert.equal(writes, before)
})

test('fatal error replaces transient retry notice and reload can recover', async () => {
  let state, calls = 0
  const polling = createExperimentPolling(async () => {
    if (++calls === 2) throw error(503)
    if (calls === 3) throw error(404)
    return { id: 'a', status: 'RUNNING' }
  }, value => { state = value })
  await polling.load('a'); await polling.poll(); await polling.poll()
  assert.equal(state.warning, false)
  assert.equal(state.error.message, '安全提示')
  await polling.load('a')
  assert.equal(state.error, null)
  assert.equal(polling.shouldPoll(), true)
})
