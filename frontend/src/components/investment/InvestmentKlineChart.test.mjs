import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('./InvestmentKlineChart.vue', import.meta.url), 'utf8')

test('K 线保留均线开关和双击恢复，不显示图表标题栏', () => {
  assert.match(source, /toggleMA/)
  assert.match(source, /visibleMAs/)
  assert.match(source, /@dblclick="restore"/)
  assert.doesNotMatch(source, /恢复视图/)
})

test('行情不足提示直接使用分析服务原因', () => {
  assert.match(source, /historyWarning/)
  assert.doesNotMatch(source, /series\.length < \d+/)
})
