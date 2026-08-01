const SUMMARY_LIMIT = 48
const LIST_LIMIT = 3

export function normalizeAiExplanation(value) {
  const source = value || {}
  const rawSummary = source.sourceType === 'AI_LEGACY'
    ? legacySummary(source)
    : source.summary || firstSentence(source.text || '')
  return {
    ...source,
    summary: String(rawSummary || '').trim().slice(0, SUMMARY_LIMIT),
    reasons: normalizeList(source.reasons),
    risks: normalizeList(source.risks),
    technicalDetails: String(source.technicalDetails || source.text || '').trim(),
  }
}

function legacySummary(source) {
  const text = `${source.summary || ''} ${source.text || ''}`.toUpperCase()
  if (/\b(SELL|REDUCE|AVOID)\b/.test(text)) {
    return '当前风险偏高，建议降低仓位或暂时回避。'
  }
  if (/\b(BUY|ADD)\b/.test(text)) {
    return '当前信号偏强，可小仓位关注，同时留意回撤风险。'
  }
  if (/\b(HOLD|WAIT|WEAK)\b/.test(text)) {
    return '当前信号偏弱，建议先观察，不急于操作。'
  }
  return firstSentence(source.summary || source.text || '')
}

function normalizeList(value) {
  if (!Array.isArray(value)) return []
  return [...new Set(value
    .filter(item => item != null)
    .map(item => String(item).trim())
    .filter(Boolean))]
    .slice(0, LIST_LIMIT)
}

function firstSentence(value) {
  const text = String(value || '').trim()
  const match = text.match(/^.*?[。！？\n]/)
  return match?.[0] || text
}
