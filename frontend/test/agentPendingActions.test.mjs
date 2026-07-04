import assert from 'node:assert/strict'
import {
  actionStatusLabel,
  actionTypeLabel,
  buildPendingActionTimeline,
  countPendingActions,
  filterPendingActions,
  parseActionPayload,
  resultEntityLabel
} from '../src/utils/agentPendingActions.js'

const actions = [
  {
    id: 1,
    actionType: 'INSTALL_AGENT_MEMORY',
    title: '写入长期记忆',
    summary: '以后回答简短',
    status: 'PENDING',
    payload: '{"memoryType":"RESPONSE_STYLE","memoryValue":"以后回答简短"}'
  },
  {
    id: 2,
    actionType: 'CREATE_AGENT_SCHEDULE',
    title: '创建周期任务',
    summary: '每周预算复盘',
    status: 'CONFIRMED',
    payload: '{"cronExpression":"0 0 9 ? * MON","taskQuery":"复盘预算","sourceReflectionId":12,"sourceTraceId":"trace-12","resultEntityType":"AGENT_SCHEDULE","resultEntityId":66}'
  },
  {
    id: 3,
    actionType: 'INSTALL_CUSTOM_SKILL',
    title: '安装 Skill',
    summary: '股票分析流程',
    status: 'PENDING',
    payload: 'not-json'
  }
]

assert.equal(actionTypeLabel('INSTALL_AGENT_MEMORY'), '写入长期记忆')
assert.equal(actionTypeLabel('UNKNOWN_ACTION'), 'UNKNOWN_ACTION')
assert.equal(actionStatusLabel('PENDING'), '待确认')
assert.equal(resultEntityLabel('AGENT_SCHEDULE'), '周期任务')
assert.deepEqual(parseActionPayload(actions[0]), {
  memoryType: 'RESPONSE_STYLE',
  memoryValue: '以后回答简短'
})
assert.deepEqual(parseActionPayload(actions[2]), {})
assert.equal(countPendingActions(actions), 2)
assert.deepEqual(
  filterPendingActions(actions, { status: 'PENDING', actionType: 'INSTALL_CUSTOM_SKILL' }).map(item => item.id),
  [3]
)
assert.deepEqual(
  filterPendingActions(actions, { keyword: '预算' }).map(item => item.id),
  [2]
)
assert.deepEqual(
  buildPendingActionTimeline(actions[2]).map(item => item.key),
  ['action', 'result']
)
assert.deepEqual(
  buildPendingActionTimeline(actions[2]).map(item => item.status),
  ['PENDING', 'WAITING']
)
assert.deepEqual(
  buildPendingActionTimeline(actions[1]).map(item => item.key),
  ['source', 'action', 'result']
)
assert.deepEqual(
  buildPendingActionTimeline(actions[1]).map(item => item.description),
  ['Reflection #12', '创建周期任务 · 已确认', '周期任务 #66']
)

console.log('agentPendingActions tests passed')
