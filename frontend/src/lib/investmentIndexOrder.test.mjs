import assert from 'node:assert/strict'
import test from 'node:test'

import { moveInvestmentIndexToPosition } from './investmentIndexOrder.js'

test('拖动指数到目标槽位时不修改输入列表', () => {
  const indexes = [
    { indexCode: 'CN_INDEX:000300' },
    { indexCode: 'GLOBAL_INDEX:NDX' },
    { indexCode: 'GLOBAL_INDEX:SPX' }
  ]

  const result = moveInvestmentIndexToPosition(indexes, 'CN_INDEX:000300', 2)

  assert.deepEqual(result.map(item => item.indexCode), [
    'GLOBAL_INDEX:NDX',
    'GLOBAL_INDEX:SPX',
    'CN_INDEX:000300'
  ])
  assert.deepEqual(indexes.map(item => item.indexCode), [
    'CN_INDEX:000300',
    'GLOBAL_INDEX:NDX',
    'GLOBAL_INDEX:SPX'
  ])
})

test('无效拖动或非有限槽位保持原顺序', () => {
  const indexes = [{ indexCode: 'CN_INDEX:000300' }]

  assert.deepEqual(moveInvestmentIndexToPosition(indexes, 'missing', 0), indexes)
  assert.deepEqual(moveInvestmentIndexToPosition(indexes, 'CN_INDEX:000300', Number.NaN), indexes)
})

test('按固定槽位移动指数，不依赖鼠标下方的 DOM 元素', () => {
  const indexes = [
    { indexCode: 'CN_INDEX:000300' },
    { indexCode: 'GLOBAL_INDEX:NDX' },
    { indexCode: 'GLOBAL_INDEX:SPX' }
  ]

  const result = moveInvestmentIndexToPosition(indexes, 'CN_INDEX:000300', 2)

  assert.deepEqual(result.map(item => item.indexCode), [
    'GLOBAL_INDEX:NDX',
    'GLOBAL_INDEX:SPX',
    'CN_INDEX:000300'
  ])
})

test('固定槽位会限制在列表范围内', () => {
  const indexes = [
    { indexCode: 'CN_INDEX:000300' },
    { indexCode: 'GLOBAL_INDEX:NDX' }
  ]

  assert.deepEqual(
    moveInvestmentIndexToPosition(indexes, 'GLOBAL_INDEX:NDX', -10).map(item => item.indexCode),
    ['GLOBAL_INDEX:NDX', 'CN_INDEX:000300']
  )
  assert.deepEqual(
    moveInvestmentIndexToPosition(indexes, 'CN_INDEX:000300', 10).map(item => item.indexCode),
    ['GLOBAL_INDEX:NDX', 'CN_INDEX:000300']
  )
})
