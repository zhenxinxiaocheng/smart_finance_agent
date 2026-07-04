export const PENDING_ACTION_TYPE_OPTIONS = [
  { value: 'RECORD_TRANSACTION', label: '记录交易' },
  { value: 'SET_BUDGET', label: '设置预算' },
  { value: 'INSTALL_CUSTOM_SKILL', label: '安装自定义 Skill' },
  { value: 'INSTALL_AGENT_MEMORY', label: '写入长期记忆' },
  { value: 'CREATE_AGENT_SCHEDULE', label: '创建周期任务' }
]

export const PENDING_ACTION_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CANCELLED', label: '已取消' }
]

export function actionTypeLabel(type) {
  return PENDING_ACTION_TYPE_OPTIONS.find(item => item.value === type)?.label || type || '未知动作'
}

export function actionStatusLabel(status) {
  return PENDING_ACTION_STATUS_OPTIONS.find(item => item.value === status)?.label || status || '未知状态'
}

export function resultEntityLabel(type) {
  const labels = {
    TRANSACTION: '交易记录',
    BUDGET: '预算',
    AGENT_SKILL: 'Agent Skill',
    AGENT_MEMORY: '长期记忆',
    AGENT_SCHEDULE: '周期任务'
  }
  return labels[type] || type || '对象'
}

export function parseActionPayload(action) {
  if (!action?.payload) return {}
  try {
    return JSON.parse(action.payload)
  } catch {
    return {}
  }
}

export function countPendingActions(actions = []) {
  return actions.filter(action => action?.status === 'PENDING').length
}

export function filterPendingActions(actions = [], filters = {}) {
  const status = normalize(filters.status)
  const actionType = normalize(filters.actionType)
  const keyword = normalize(filters.keyword).toLowerCase()
  return actions.filter(action => {
    const matchStatus = !status || action?.status === status
    const matchType = !actionType || action?.actionType === actionType
    const payload = parseActionPayload(action)
    const text = [
      action?.title,
      action?.summary,
      action?.actionType,
      action?.status,
      ...Object.values(payload).map(value => String(value))
    ].filter(Boolean).join(' ').toLowerCase()
    const matchKeyword = !keyword || text.includes(keyword)
    return matchStatus && matchType && matchKeyword
  })
}

export function buildPendingActionTimeline(action) {
  if (!action) return []
  const payload = parseActionPayload(action)
  const steps = []
  if (payload.sourceReflectionId || payload.sourceTraceId) {
    steps.push({
      key: 'source',
      title: '来源反思',
      status: 'DONE',
      description: payload.sourceReflectionId
        ? `Reflection #${payload.sourceReflectionId}`
        : '由 Agent 反思生成',
      meta: payload.sourceTraceId ? `traceId: ${payload.sourceTraceId}` : ''
    })
  }
  steps.push({
    key: 'action',
    title: '待确认动作',
    status: action.status || 'PENDING',
    description: `${actionTypeLabel(action.actionType)} · ${actionStatusLabel(action.status)}`,
    meta: action.id ? `Action #${action.id}` : ''
  })
  if (payload.resultEntityType) {
    steps.push({
      key: 'result',
      title: '落地对象',
      status: 'DONE',
      description: `${resultEntityLabel(payload.resultEntityType)} #${payload.resultEntityId || '-'}`,
      meta: payload.resultEntityType
    })
  } else if (action.status === 'PENDING') {
    steps.push({
      key: 'result',
      title: '落地对象',
      status: 'WAITING',
      description: '确认执行后生成',
      meta: ''
    })
  }
  return steps
}

function normalize(value) {
  return value == null ? '' : String(value).trim()
}
