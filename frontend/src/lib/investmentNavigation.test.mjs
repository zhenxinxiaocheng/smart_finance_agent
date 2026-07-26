import assert from 'node:assert/strict'
import test from 'node:test'

import {
  clearInvestmentDetailPath,
  rememberInvestmentDetailPath,
  resolveInvestmentEntryPath,
} from './investmentNavigation.js'

function storage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
  }
}

test('会话内返回投资分析时恢复最后访问的资产详情', () => {
  const session = storage()

  rememberInvestmentDetailPath('/stocks/6', session)

  assert.equal(resolveInvestmentEntryPath(session), '/stocks/6')
})

test('主动返回资产列表后不再恢复旧详情', () => {
  const session = storage()
  rememberInvestmentDetailPath('/stocks/6', session)

  clearInvestmentDetailPath(session)

  assert.equal(resolveInvestmentEntryPath(session), '/stocks')
})

test('非法或过期路径不会成为投资分析入口', () => {
  const session = storage()
  rememberInvestmentDetailPath('/stocks', session)
  assert.equal(resolveInvestmentEntryPath(session), '/stocks')
  session.setItem('investment:last-detail-path', '/stocks/6/settings')
  assert.equal(resolveInvestmentEntryPath(session), '/stocks')
})
