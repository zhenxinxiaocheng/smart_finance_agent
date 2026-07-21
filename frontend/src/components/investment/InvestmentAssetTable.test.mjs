import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const tableSource = readFileSync(new URL('./InvestmentAssetTable.vue', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../../views/InvestmentAnalysis.vue', import.meta.url), 'utf8')

test('资产列表不再提供单独刷新按钮', () => {
  assert.doesNotMatch(tableSource, /刷新行情|RefreshCw|\$emit\('sync'/)
  assert.doesNotMatch(pageSource, /@sync=|syncInvestmentAssetAPI|function syncAsset/)
})

test('点击资产进入详情，编辑按钮保持独立事件', () => {
  assert.match(tableSource, /\$emit\('select', asset\)/)
  assert.match(tableSource, /\$emit\('edit', asset\)/)
  assert.match(pageSource, /router\.push\(`\/stocks\/\$\{asset\.id\}`\)/)
  assert.match(pageSource, /@edit="openEditor"/)
})
