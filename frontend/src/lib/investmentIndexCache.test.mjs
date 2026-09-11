import assert from 'node:assert/strict'
import test from 'node:test'

import {
  readInvestmentIndexCache,
  writeInvestmentIndexCache
} from './investmentIndexCache.js'

class MemoryStorage {
  #values = new Map()

  getItem(key) {
    return this.#values.get(key) ?? null
  }

  setItem(key, value) {
    this.#values.set(key, value)
  }
}

test('指数缓存按账户保留最近一次成功结果', () => {
  const storage = new MemoryStorage()
  const indexes = [{ indexCode: 'GLOBAL_INDEX:NDX', latestPrice: '29143.3301' }]

  writeInvestmentIndexCache(storage, 'zxxc', indexes)

  assert.deepEqual(readInvestmentIndexCache(storage, 'zxxc'), indexes)
  assert.deepEqual(readInvestmentIndexCache(storage, 'another-user'), [])
})

test('损坏的指数缓存按空列表处理', () => {
  const storage = new MemoryStorage()
  storage.setItem('investment-index-watchlist:v1:zxxc', '{invalid')

  assert.deepEqual(readInvestmentIndexCache(storage, 'zxxc'), [])
})
