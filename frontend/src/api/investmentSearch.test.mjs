import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('自选合并搜索包含股票与基金，允许美股并排除指数', async () => {
  const source = readFileSync(new URL('./investment.js', import.meta.url), 'utf8')
    .replace(/^import .+$/gm, '').replace(/export const /g, 'const ')
  const requests = []
  const search = new Function('request', `${source}\nreturn searchInvestmentAssetProductsAPI`)(
    { get: async (url, options) => { requests.push({ url, ...options }); return {} } })
  await search({ keyword: '英伟达', page: 1 })
  assert.equal(requests[0].params.search, '英伟达')
  assert.equal(requests[0].params.assetType, 'STOCK,FUND')
  assert.equal(requests[0].params.marketGroup, undefined)
})
