export const CRON_FREQUENCY_OPTIONS = [
  { label: '每天', value: 'daily' },
  { label: '每周', value: 'weekly' },
  { label: '每月', value: 'monthly' },
  { label: '自定义', value: 'custom' }
]

export const WEEKDAY_OPTIONS = [
  { label: '周一', value: 'MON', text: '一' },
  { label: '周二', value: 'TUE', text: '二' },
  { label: '周三', value: 'WED', text: '三' },
  { label: '周四', value: 'THU', text: '四' },
  { label: '周五', value: 'FRI', text: '五' },
  { label: '周六', value: 'SAT', text: '六' },
  { label: '周日', value: 'SUN', text: '日' }
]

const TIME_PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/

export function buildCronExpression(template) {
  const { hour, minute } = parseTime(template?.time)
  const frequency = template?.frequency || 'weekly'

  if (frequency === 'daily') {
    return `0 ${minute} ${hour} * * ?`
  }

  if (frequency === 'weekly') {
    const weekday = normalizeWeekday(template?.dayOfWeek)
    return `0 ${minute} ${hour} ? * ${weekday}`
  }

  if (frequency === 'monthly') {
    const day = normalizeDayOfMonth(template?.dayOfMonth)
    return `0 ${minute} ${hour} ${day} * ?`
  }

  throw new Error(`unsupported frequency: ${frequency}`)
}

export function describeCronTemplate(template) {
  const time = normalizeTime(template?.time)
  const frequency = template?.frequency || 'weekly'

  if (frequency === 'daily') {
    return `每天 ${time} 执行`
  }

  if (frequency === 'weekly') {
    const weekday = WEEKDAY_OPTIONS.find(item => item.value === normalizeWeekday(template?.dayOfWeek))
    return `每周${weekday.text} ${time} 执行`
  }

  if (frequency === 'monthly') {
    return `每月 ${normalizeDayOfMonth(template?.dayOfMonth)} 日 ${time} 执行`
  }

  return '使用自定义 cron 表达式'
}

function parseTime(time) {
  const normalized = normalizeTime(time)
  const match = normalized.match(TIME_PATTERN)
  return {
    hour: Number(match[1]),
    minute: Number(match[2])
  }
}

function normalizeTime(time) {
  const value = typeof time === 'string' ? time.trim() : ''
  if (!TIME_PATTERN.test(value)) {
    throw new Error('time must use HH:mm')
  }
  return value
}

function normalizeWeekday(dayOfWeek) {
  const value = typeof dayOfWeek === 'string' ? dayOfWeek.trim().toUpperCase() : 'MON'
  if (!WEEKDAY_OPTIONS.some(item => item.value === value)) {
    throw new Error('dayOfWeek must be MON, TUE, WED, THU, FRI, SAT, or SUN')
  }
  return value
}

function normalizeDayOfMonth(dayOfMonth) {
  const value = Number(dayOfMonth || 1)
  if (!Number.isInteger(value) || value < 1 || value > 31) {
    throw new Error('dayOfMonth must be an integer between 1 and 31')
  }
  return value
}
