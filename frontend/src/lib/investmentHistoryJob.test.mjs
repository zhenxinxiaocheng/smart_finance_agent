import assert from 'node:assert/strict'
import test from 'node:test'
import { describeHistoryJob, needsHistoryPreparation } from './investmentHistoryJob.js'

test('describes every backend history job status in user facing language', () => {
  for (const status of ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'PARTIAL', 'SUCCEEDED', 'SKIPPED', 'FAILED']) {
    assert.equal(typeof describeHistoryJob(status), 'string')
  }
  assert.equal(describeHistoryJob('SUCCEEDED'), '历史数据已补齐')
  assert.equal(describeHistoryJob(undefined), '尚未开始准备历史数据')
})

test('only assets without any有效 history ask for preparation', () => {
  assert.equal(needsHistoryPreparation('PREPARING', null), true)
  assert.equal(needsHistoryPreparation('PREPARING', '2026-09-30'), false)
  assert.equal(needsHistoryPreparation('READY', '2026-09-24'), false)
  assert.equal(needsHistoryPreparation('READY', null), false)
  assert.equal(needsHistoryPreparation('BLOCKED', '2026-09-24'), false)
  assert.equal(needsHistoryPreparation(undefined, null), true)
})
