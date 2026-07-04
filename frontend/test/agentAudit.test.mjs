import assert from 'node:assert/strict'
import {
  buildAgentAuditEvents,
  buildAgentAuditSummary
} from '../src/utils/agentAudit.js'

const reflections = [
  {
    id: 1,
    title: '可沉淀为周期任务',
    suggestionType: 'SCHEDULE_CANDIDATE',
    status: 'OPEN',
    traceId: 'trace-1',
    createdAt: '2026-07-04T09:00:00'
  },
  {
    id: 2,
    title: '周期任务失败风险',
    suggestionType: 'RISK_WARNING',
    status: 'DISMISSED',
    createdAt: '2026-07-04T08:00:00'
  }
]

const actions = [
  {
    id: 3,
    actionType: 'CREATE_AGENT_SCHEDULE',
    status: 'PENDING',
    title: '确认创建周期任务',
    payload: '{"sourceTraceId":"trace-1"}',
    updatedAt: '2026-07-04T10:00:00'
  },
  {
    id: 4,
    actionType: 'INSTALL_AGENT_MEMORY',
    status: 'CONFIRMED',
    title: '确认写入长期记忆',
    payload: '{"resultEntityType":"AGENT_MEMORY","resultEntityId":9}',
    updatedAt: '2026-07-04T11:00:00'
  }
]

const schedules = [
  {
    id: 5,
    name: '每周预算复盘',
    enabled: 0,
    lastStatus: 'FAILED',
    runCount: 3,
    consecutiveFailures: 3,
    updatedAt: '2026-07-04T12:00:00'
  },
  {
    id: 6,
    name: '每日支出检查',
    enabled: 1,
    lastStatus: 'SUCCESS',
    runCount: 5,
    consecutiveFailures: 0,
    updatedAt: '2026-07-04T07:00:00'
  }
]

assert.deepEqual(buildAgentAuditSummary({ reflections, actions, schedules }), {
  openReflections: 1,
  pendingActions: 1,
  failedSchedules: 1,
  activeSchedules: 1
})

const events = buildAgentAuditEvents({ reflections, actions, schedules })
assert.equal(events[0].id, 'schedule-5')
assert.equal(events[1].id, 'action-4')
assert.equal(events.find(item => item.id === 'action-4').summary, '写入长期记忆 · 已确认 -> 长期记忆 #9')
assert.equal(events.find(item => item.id === 'reflection-2').severity, 'warning')
assert.deepEqual(events.find(item => item.id === 'action-3').target, {
  path: '/pending-actions',
  query: { actionId: 3 }
})

console.log('agentAudit tests passed')
