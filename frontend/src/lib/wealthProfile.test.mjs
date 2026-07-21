import assert from 'node:assert/strict'
import test from 'node:test'
import { hasCashBaselineChanged } from './wealthProfile.js'

test('未初始化时允许用零元建立现金基准', () => {
  assert.equal(hasCashBaselineChanged(false, null, '0'), true)
})

test('金额没有变化时不重置现金基准时间', () => {
  assert.equal(hasCashBaselineChanged(true, 80000, '80000'), false)
})

test('现金金额变化时需要重建基准', () => {
  assert.equal(hasCashBaselineChanged(true, 80000, '82000'), true)
})

test('空输入不保存现金基准', () => {
  assert.equal(hasCashBaselineChanged(false, null, ''), false)
})
