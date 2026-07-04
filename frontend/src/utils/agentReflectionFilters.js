export const REFLECTION_TYPE_OPTIONS = [
  { value: 'MEMORY_CANDIDATE', label: '记忆候选' },
  { value: 'SKILL_CANDIDATE', label: 'Skill 候选' },
  { value: 'SCHEDULE_CANDIDATE', label: '周期任务候选' },
  { value: 'RISK_WARNING', label: '风险警告' }
]

export const REFLECTION_STATUS_OPTIONS = [
  { value: 'OPEN', label: '待处理' },
  { value: 'ACCEPTED', label: '已采纳' },
  { value: 'DISMISSED', label: '已忽略' }
]

const ACTIONABLE_TYPES = new Set(['MEMORY_CANDIDATE', 'SKILL_CANDIDATE', 'SCHEDULE_CANDIDATE'])

export function reflectionTypeLabel(type) {
  return REFLECTION_TYPE_OPTIONS.find(item => item.value === type)?.label || type || '未知类型'
}

export function reflectionStatusLabel(status) {
  return REFLECTION_STATUS_OPTIONS.find(item => item.value === status)?.label || status || '未知状态'
}

export function canAcceptReflection(reflection) {
  return reflection?.status === 'OPEN' && ACTIONABLE_TYPES.has(reflection?.suggestionType)
}

export function countOpenActionableReflections(reflections = []) {
  return reflections.filter(canAcceptReflection).length
}

export function filterAgentReflections(reflections = [], filters = {}) {
  const status = normalize(filters.status)
  const suggestionType = normalize(filters.suggestionType)
  const keyword = normalize(filters.keyword).toLowerCase()
  return reflections.filter(reflection => {
    const matchStatus = !status || reflection?.status === status
    const matchType = !suggestionType || reflection?.suggestionType === suggestionType
    const text = [
      reflection?.title,
      reflection?.summary,
      reflection?.traceId,
      reflection?.suggestionType,
      reflection?.status
    ].filter(Boolean).join(' ').toLowerCase()
    const matchKeyword = !keyword || text.includes(keyword)
    return matchStatus && matchType && matchKeyword
  })
}

export function buildScheduleDraftFromReflection(reflection) {
  const payload = readPayload(reflection?.payload)
  const query = normalize(payload.query) || normalize(reflection?.summary)
  return {
    name: '运行反思周期任务',
    description: normalize(reflection?.summary) || query,
    cronExpression: inferCronExpression(query),
    taskQuery: query,
    timezone: 'Asia/Shanghai'
  }
}

function inferCronExpression(text) {
  const value = normalize(text)
  if (value.includes('每天')) return '0 0 9 * * ?'
  if (value.includes('每周')) return '0 0 9 ? * MON'
  if (value.includes('每月')) return '0 0 9 1 * ?'
  return ''
}

function readPayload(payload) {
  if (!payload) return {}
  try {
    return JSON.parse(payload)
  } catch {
    return {}
  }
}

function normalize(value) {
  return value == null ? '' : String(value).trim()
}
