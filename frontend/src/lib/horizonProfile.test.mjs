import assert from 'node:assert/strict'
import test from 'node:test'

import {
  normalizeHorizonProfile,
  profileSavePayload,
  validateHorizonSettings,
} from './horizonProfile.js'

test('保留任意标签、重叠区间和大周期', () => {
  const profile = normalizeHorizonProfile({ settings: [
    { code: 'WAVE', displayName: '我的短期', sortOrder: 20, minHoldingDays: 10, maxHoldingDays: 100 },
    { code: 'SLOW', displayName: '我的长期', sortOrder: 10, minHoldingDays: 50, maxHoldingDays: 900 },
  ] })

  assert.deepEqual(profile.settings.map(item => item.code), ['SLOW', 'WAVE'])
  assert.equal(validateHorizonSettings(profile.settings), '')
})

test('只拒绝重复代码和反向区间等结构错误', () => {
  const error = validateHorizonSettings([
    { code: 'X', displayName: '一', minHoldingDays: 20, maxHoldingDays: 10 },
    { code: 'x', displayName: '二', minHoldingDays: 30, maxHoldingDays: 50 },
  ])

  assert.match(error, /最小天数/)
  assert.match(error, /重复/)
})

test('保存载荷不携带前端默认值或只读来源字段', () => {
  const payload = profileSavePayload({ settings: [
    { code: 'POSITION', displayName: '配置', sortOrder: 3, minHoldingDays: 80, maxHoldingDays: 260, primary: true, sourceScope: 'GLOBAL' },
  ] })

  assert.deepEqual(payload, { settings: [
    { code: 'POSITION', displayName: '配置', sortOrder: 3, minHoldingDays: 80, maxHoldingDays: 260, primary: true },
  ] })
})
