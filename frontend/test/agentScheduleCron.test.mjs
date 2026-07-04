import assert from 'node:assert/strict'
import {
  buildCronExpression,
  describeCronTemplate
} from '../src/utils/agentScheduleCron.js'

assert.equal(
  buildCronExpression({ frequency: 'daily', time: '09:30' }),
  '0 30 9 * * ?'
)
assert.equal(
  describeCronTemplate({ frequency: 'daily', time: '09:30' }),
  '每天 09:30 执行'
)

assert.equal(
  buildCronExpression({ frequency: 'weekly', time: '18:05', dayOfWeek: 'TUE' }),
  '0 5 18 ? * TUE'
)
assert.equal(
  describeCronTemplate({ frequency: 'weekly', time: '18:05', dayOfWeek: 'TUE' }),
  '每周二 18:05 执行'
)

assert.equal(
  buildCronExpression({ frequency: 'monthly', time: '08:15', dayOfMonth: 5 }),
  '0 15 8 5 * ?'
)
assert.equal(
  describeCronTemplate({ frequency: 'monthly', time: '08:15', dayOfMonth: 5 }),
  '每月 5 日 08:15 执行'
)

assert.throws(
  () => buildCronExpression({ frequency: 'weekly', time: '24:00', dayOfWeek: 'MON' }),
  /time must use HH:mm/
)

console.log('agentScheduleCron tests passed')
