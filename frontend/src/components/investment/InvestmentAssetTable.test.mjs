import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'

const tableSource = readFileSync(new URL('./InvestmentAssetTable.vue', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../../views/InvestmentAnalysis.vue', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../../api/investment.js', import.meta.url), 'utf8')

test('行情同步成功不宣称全部数据及分析正常', () => {
  assert.match(tableSource, /行情状态/)
  assert.match(tableSource, /SUCCESS: '行情已同步'/)
  assert.doesNotMatch(tableSource, /数据正常/)
})

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

test('资产页按交易时段刷新行情，焦点恢复只读取已有数据', () => {
  assert.match(apiSource, /refreshInvestmentAssetsAPI\s*=.*\/investment\/assets\/refresh/)
  assert.match(pageSource, /refreshInvestmentAssetsAPI/)
  assert.match(pageSource, /startInvestmentRealtimePolling\(force\s*=>\s*\{/)
  assert.match(pageSource, /isInvestmentQuoteSessionActive\(assets.value\)\) return refreshAssets\(force,\s*true\)/)
  assert.match(pageSource, /@asset-added="refreshAfterAssetChange"/)
  assert.match(pageSource, /refreshAfterAssetChange[\s\S]*refreshAssets\(true,\s*true,\s*true\)/)
  const calls = []
  const start = pageSource.indexOf('function refreshOnFocus()')
  const end = pageSource.indexOf('function refreshAfterAssetChange()', start)
  assert.ok(start >= 0 && end > start)
  vm.runInNewContext(pageSource.slice(start, end) + '\nrefreshOnFocus()', {
    loadAssets: silent => { calls.push(['assets', silent]); return Promise.resolve() },
    loadIndexes: silent => { calls.push(['indexes', silent]); return Promise.resolve() },
    refreshAssets: () => { assert.fail('焦点恢复不能请求批量采集') },
  })
  assert.deepEqual(calls, [['assets', true], ['indexes', true]])
  assert.match(pageSource, /onUnmounted[\s\S]*refreshAssets\.dispose\(\)/)
})
