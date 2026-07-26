import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const tableSource = readFileSync(new URL('./InvestmentAssetTable.vue', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../../views/InvestmentAnalysis.vue', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../../api/investment.js', import.meta.url), 'utf8')

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

test('资产页通过批量刷新接口获取最新数据，并区分强制与定时刷新', () => {
  assert.match(apiSource, /refreshInvestmentAssetsAPI\s*=.*\/investment\/assets\/refresh/)
  assert.match(pageSource, /refreshInvestmentAssetsAPI/)
  assert.match(pageSource, /startInvestmentRealtimePolling\(force\s*=>\s*refreshAssets\(force,\s*true\)\)/)
  assert.match(pageSource, /refreshAssets\(true,\s*true\)/)
  assert.match(pageSource, /@added="refreshAfterAssetChange"/)
  assert.match(pageSource, /refreshAfterAssetChange[\s\S]*refreshAssets\(true,\s*true,\s*true\)/)
  assert.match(pageSource, /catch\(\(\)\s*=>\s*loadAssets\(\)\.catch\(\(\)\s*=>\s*\{\}\)\)/)
  assert.match(pageSource, /onUnmounted[\s\S]*refreshAssets\.dispose\(\)/)
})
