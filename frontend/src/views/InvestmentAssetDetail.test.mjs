import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('./InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const template = source.match(/<template>[\s\S]*<\/template>/)?.[0] || ''

test('用户页面不展示内部数据质量诊断', () => {
  assert.doesNotMatch(template, /datasetVersion/)
  assert.doesNotMatch(template, /qualityIssues/)
  assert.doesNotMatch(template, /qualityProviderWarnings/)
  assert.doesNotMatch(template, /dataQualityError/)
})

test('数据异常时只展示用户可理解的稳定状态', () => {
  assert.match(template, /使用可靠缓存|数据准备中/)
  assert.doesNotMatch(template, /数据质量门禁|BLOCKED/)
})

test('历史任务使用用户可理解的状态提示', () => {
  assert.match(template, /正在排队补齐历史数据/)
  assert.match(template, /正在补齐历史数据/)
  assert.match(template, /历史数据准备将自动重试/)
  assert.match(template, /历史数据已部分补齐/)
  assert.match(template, /历史数据准备未完成/)
  assert.doesNotMatch(template, /errorMessage/)
})
