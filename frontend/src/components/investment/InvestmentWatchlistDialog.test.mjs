import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const dialogSource = readFileSync(new URL('./InvestmentWatchlistDialog.vue', import.meta.url), 'utf8')
const assetTableSource = readFileSync(new URL('./InvestmentAssetTable.vue', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../../views/InvestmentAnalysis.vue', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../../api/investment.js', import.meta.url), 'utf8')

test('资产表底部入口打开股票基金指数共用的添加对话框', () => {
  assert.doesNotMatch(pageSource, />自选</)
  assert.match(assetTableSource, /title="新增资产"/)
  assert.match(pageSource, /@add="openAssetWatchlist"/)
  assert.match(pageSource, /InvestmentWatchlistDialog/)
  assert.match(dialogSource, /value="STOCK"/)
  assert.match(dialogSource, /value="MUTUAL_FUND"/)
  assert.match(dialogSource, /value="INDEX"/)
})

test('投资页顶部展示独立指数自选列表', () => {
  assert.match(pageSource, /InvestmentIndexStrip/)
  assert.match(apiSource, /listInvestmentIndexesAPI/)
  assert.match(apiSource, /searchInvestmentIndexesAPI/)
  assert.match(apiSource, /addInvestmentIndexAPI/)
  assert.match(apiSource, /reorderInvestmentIndexesAPI/)
  assert.match(apiSource, /deleteInvestmentIndexAPI/)
})
