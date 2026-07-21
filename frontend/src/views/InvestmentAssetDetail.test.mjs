import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('./InvestmentAssetDetail.vue', import.meta.url), 'utf8')

test('用户页面不展示内部数据质量诊断', () => {
  assert.doesNotMatch(source, /datasetVersion/)
  assert.doesNotMatch(source, /qualityIssues/)
  assert.doesNotMatch(source, /qualityProviderWarnings/)
  assert.doesNotMatch(source, /dataQualityError/)
})

test('数据异常时只展示用户可理解的稳定状态', () => {
  assert.match(source, /使用可靠缓存|数据准备中/)
  assert.doesNotMatch(source, /数据质量门禁|BLOCKED/)
})
