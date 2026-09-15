import assert from 'node:assert/strict'
import test from 'node:test'
import { pageKey, navigationRecord, returnDestination } from './navigation.js'

const route = url => { const u = new URL(url, 'https://local'); return { path: u.pathname, fullPath: u.pathname + u.search, query: Object.fromEntries(u.searchParams) } }
test('details remember each real source including all query state', () => {
  for (const source of ['/quant/tasks?type=backtests&id=b&status=SUCCEEDED', '/quant/strategies/s?tab=backtests&version=all', '/quant/experiments/e', '/quant/deployments?id=d', '/quant/factors?q=test&id=f']) {
    const target = route('/quant/tasks?type=backtests&id=other')
    const record = navigationRecord(target, route(source), { position: 4 }, { position: 5 })
    assert.deepEqual(record.source, { path: source, position: 4 })
    assert.deepEqual(returnDestination(target, { position: 5, quantNavigation: record }, '/quant/tasks'), { delta: -1 })
  }
})
test('tab changes preserve the original source and return skips local navigation', () => {
  const a = route('/quant/tasks?id=b'), b = route('/quant/strategies/s'), c = route('/quant/strategies/s?tab=training')
  const record = navigationRecord(b, a, { position: 2 }, { position: 3 })
  const next = navigationRecord(c, b, { position: 3, quantNavigation: record }, { position: 4 })
  assert.equal(pageKey(b), pageKey(c))
  assert.deepEqual(returnDestination(c, { position: 4, quantNavigation: next }, '/quant'), { delta: -2 })
})
test('refresh and browser back preserve the entry, direct/external/self sources fall back', () => {
  const target = route('/quant/strategies/s'), empty = route('/')
  const record = { key: pageKey(target), source: { path: '/quant/tasks?id=b', position: 1 } }
  assert.deepEqual(navigationRecord(target, empty, {}, { position: 2, quantNavigation: record }), record)
  for (const path of ['https://evil.test', '//evil.test', '/login', '/quant/strategies/s']) {
    assert.deepEqual(returnDestination(target, { position: 2, quantNavigation: { ...record, source: { path, position: 1 } } }, '/quant'), { path: '/quant' })
  }
  assert.deepEqual(returnDestination(target, {}, '/quant'), { path: '/quant' })
})
test('same-entry replacement uses source URL without nesting query or returning in a loop', () => {
  const target = route('/quant/tasks?id=b')
  const state = { position: 2, quantNavigation: { key: pageKey(target), source: { path: '/quant/strategies/s?tab=backtests', position: 2 } } }
  assert.deepEqual(returnDestination(target, state, '/quant/tasks'), { path: '/quant/strategies/s?tab=backtests' })
})
test('saving a new strategy replaces the form without losing the list source', () => {
  const list=route('/quant?q=trend'), form=route('/quant/strategies/new'), saved=route('/quant/strategies/s?tab=backtests')
  const record=navigationRecord(form,list,{position:1},{position:2})
  const next=navigationRecord(saved,form,{position:2,quantNavigation:record},{position:2,quantNavigation:record})
  assert.deepEqual(next.source,{path:list.fullPath,position:1})
})
