import assert from 'node:assert/strict'
import {
  buildScheduleDraftFromReflection,
  countOpenActionableReflections,
  filterAgentReflections,
  reflectionTypeLabel
} from '../src/utils/agentReflectionFilters.js'

const reflections = [
  {
    id: 1,
    status: 'OPEN',
    suggestionType: 'MEMORY_CANDIDATE',
    title: '可沉淀为长期记忆',
    summary: '用户偏好：以后回答都简短一点'
  },
  {
    id: 2,
    status: 'ACCEPTED',
    suggestionType: 'SKILL_CANDIDATE',
    title: '可沉淀为自定义 Skill',
    summary: '股票分析固定流程'
  },
  {
    id: 3,
    status: 'OPEN',
    suggestionType: 'RISK_WARNING',
    title: 'Agent 运行失败风险',
    summary: '工具 timeout'
  }
]

assert.deepEqual(
  filterAgentReflections(reflections, { status: 'OPEN', suggestionType: 'MEMORY_CANDIDATE' }).map(item => item.id),
  [1]
)

assert.deepEqual(
  filterAgentReflections(reflections, { keyword: '股票' }).map(item => item.id),
  [2]
)

assert.equal(countOpenActionableReflections(reflections), 1)
assert.equal(reflectionTypeLabel('SCHEDULE_CANDIDATE'), '周期任务候选')
assert.equal(reflectionTypeLabel('UNKNOWN_TYPE'), 'UNKNOWN_TYPE')

assert.deepEqual(
  buildScheduleDraftFromReflection({
    suggestionType: 'SCHEDULE_CANDIDATE',
    summary: '这次需求像是一个可重复执行的监控或复盘任务：以后每周复盘预算',
    payload: '{"query":"以后每周复盘预算"}'
  }),
  {
    name: '运行反思周期任务',
    description: '这次需求像是一个可重复执行的监控或复盘任务：以后每周复盘预算',
    cronExpression: '0 0 9 ? * MON',
    taskQuery: '以后每周复盘预算',
    timezone: 'Asia/Shanghai'
  }
)

assert.equal(
  buildScheduleDraftFromReflection({
    suggestionType: 'SCHEDULE_CANDIDATE',
    summary: '以后定期检查预算风险',
    payload: '{"query":"以后定期检查预算风险"}'
  }).cronExpression,
  ''
)

console.log('agentReflectionFilters tests passed')
