import assert from 'node:assert/strict'

import { normalizeAiExplanation } from './investmentExplanation.js'

const normalized = normalizeAiExplanation({
  summary: '短期偏弱，先观察',
  reasons: ['趋势走弱', '波动升高', '回撤扩大', '多余原因'],
  risks: ['可能继续下跌', '数据仍在更新', '流动性偏低', '多余风险'],
  technicalDetails: '只用于展开查看',
})

assert.equal(normalized.summary, '短期偏弱，先观察')
assert.deepEqual(normalized.reasons, ['趋势走弱', '波动升高', '回撤扩大'])
assert.deepEqual(normalized.risks, ['可能继续下跌', '数据仍在更新', '流动性偏低'])

const legacy = normalizeAiExplanation({
  sourceType: 'AI_LEGACY',
  text: '技术分析综合评分为33.4，明确判定为“WEAK”（偏弱），但系统给出的操作指令为“HOLD”（持有）。后面是很长的技术细节。',
})
assert.equal(legacy.summary, '当前信号偏弱，建议先观察，不急于操作。')
assert.equal(legacy.reasons.length, 0)
assert.match(legacy.technicalDetails, /WEAK/)

console.log('investmentExplanation tests passed')
