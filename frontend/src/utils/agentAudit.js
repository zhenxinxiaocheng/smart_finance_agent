import { actionStatusLabel, actionTypeLabel, parseActionPayload, resultEntityLabel } from './agentPendingActions.js'
import { reflectionStatusLabel, reflectionTypeLabel } from './agentReflectionFilters.js'

export function buildAgentAuditSummary({ reflections = [], actions = [], schedules = [] } = {}) {
  return {
    openReflections: reflections.filter(item => item.status === 'OPEN').length,
    pendingActions: actions.filter(item => item.status === 'PENDING').length,
    failedSchedules: schedules.filter(item => item.lastStatus === 'FAILED' || isCircuitBroken(item)).length,
    activeSchedules: schedules.filter(item => Number(item.enabled || 0) === 1).length
  }
}

export function buildAgentAuditEvents({ reflections = [], actions = [], schedules = [] } = {}) {
  return [
    ...reflections.map(reflectionEvent),
    ...actions.map(actionEvent),
    ...schedules.map(scheduleEvent)
  ]
    .filter(Boolean)
    .sort((a, b) => timestamp(b.time) - timestamp(a.time))
    .slice(0, 50)
}

function reflectionEvent(reflection) {
  return {
    id: `reflection-${reflection.id}`,
    source: 'REFLECTION',
    title: reflection.title || reflectionTypeLabel(reflection.suggestionType),
    summary: reflection.summary || reflectionTypeLabel(reflection.suggestionType),
    status: reflectionStatusLabel(reflection.status),
    severity: reflection.suggestionType === 'RISK_WARNING' ? 'warning' : statusSeverity(reflection.status),
    time: reflection.updatedAt || reflection.createdAt,
    target: { path: '/reflections', query: { status: '', reflectionId: reflection.id } },
    traceId: reflection.traceId
  }
}

function actionEvent(action) {
  const payload = parseActionPayload(action)
  const result = payload.resultEntityType
    ? ` -> ${resultEntityLabel(payload.resultEntityType)} #${payload.resultEntityId || '-'}`
    : ''
  return {
    id: `action-${action.id}`,
    source: 'ACTION',
    title: action.title || actionTypeLabel(action.actionType),
    summary: `${actionTypeLabel(action.actionType)} · ${actionStatusLabel(action.status)}${result}`,
    status: actionStatusLabel(action.status),
    severity: statusSeverity(action.status),
    time: action.updatedAt || action.createdAt,
    target: { path: '/pending-actions', query: { actionId: action.id } },
    traceId: payload.sourceTraceId
  }
}

function scheduleEvent(schedule) {
  const failures = Number(schedule.consecutiveFailures || 0)
  const status = isCircuitBroken(schedule) ? '已熔断' : schedule.lastStatus === 'FAILED' ? '最近失败' : Number(schedule.enabled || 0) === 1 ? '运行中' : '已停用'
  return {
    id: `schedule-${schedule.id}`,
    source: 'SCHEDULE',
    title: schedule.name || '周期任务',
    summary: `${status} · 已运行 ${Number(schedule.runCount || 0)} 次 · 连续失败 ${failures} 次`,
    status,
    severity: status === '已熔断' || status === '最近失败' ? 'warning' : Number(schedule.enabled || 0) === 1 ? 'success' : 'muted',
    time: schedule.updatedAt || schedule.lastRunAt || schedule.createdAt,
    target: { path: '/schedules', query: { scheduleId: schedule.id } },
    traceId: schedule.traceId
  }
}

function isCircuitBroken(schedule) {
  return Number(schedule?.enabled || 0) === 0 && Number(schedule?.consecutiveFailures || 0) >= 3
}

function statusSeverity(status) {
  if (status === 'PENDING' || status === 'OPEN') return 'pending'
  if (status === 'CONFIRMED' || status === 'ACCEPTED') return 'success'
  if (status === 'CANCELLED' || status === 'DISMISSED') return 'muted'
  return 'muted'
}

function timestamp(value) {
  const parsed = value ? Date.parse(value) : 0
  return Number.isFinite(parsed) ? parsed : 0
}
