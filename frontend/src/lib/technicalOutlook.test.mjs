import assert from 'node:assert/strict'
import test from 'node:test'

import {
  confidenceLabel,
  directionLabel,
  invalidationText,
} from './technicalOutlook.js'

test('把模型内部方向转换为普通用户可读的走势', () => {
  assert.equal(directionLabel('BULLISH'), '看涨')
  assert.equal(directionLabel('LEAN_BEARISH'), '偏弱')
  assert.equal(directionLabel('SIDEWAYS'), '横盘观察')
  assert.equal(confidenceLabel('HIGH'), '高置信度')
})

test('失效条件根据返回类型和价格动态展示', () => {
  const format = value => Number(value).toFixed(2)
  assert.equal(invalidationText({ type: 'BELOW', price: 12.345 }, format), '跌破 12.35')
  assert.equal(invalidationText({ type: 'ABOVE', price: 16 }, format), '突破 16.00')
  assert.equal(
    invalidationText({ type: 'OUTSIDE_RANGE', lower: 10, upper: 20 }, format),
    '离开 10.00 – 20.00 区间',
  )
})
