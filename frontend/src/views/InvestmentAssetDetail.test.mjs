import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'

const source = readFileSync(new URL('./InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const template = source.match(/<template>[\s\S]*<\/template>/)?.[0] || ''

function loader(existing, request) {
  const context = vm.createContext({
    detail: { value: existing }, loading: { value: false }, reloading: { value: false },
    error: { value: '' }, detailRequestToken: 0, route: { params: { assetId: '11' } },
    isCurrentAsset: id => String(id) === '11', getInvestmentAssetDetailAPI: request,
    syncHistoryJobPolling() {}
  })
  const start = source.indexOf('async function loadAll(')
  const end = source.indexOf('\nasync function refreshData(', start)
  vm.runInContext(source.slice(start, end), context)
  return context
}

test('首次加载显示 Skeleton，后台重新加载保留现有内容', async () => {
  let finish
  const pending = new Promise(resolve => { finish = resolve })
  const existing = { asset: { id: 11, name: '当前资产' } }
  const refresh = loader(existing, () => pending)
  const done = refresh.loadAll()
  assert.equal(refresh.loading.value, false)
  assert.equal(refresh.reloading.value, true)
  assert.equal(refresh.detail.value, existing)
  finish({ data: { asset: { id: 11, name: '更新后的资产' } } })
  await done
  assert.equal(refresh.reloading.value, false)
  assert.equal(refresh.detail.value.asset.name, '更新后的资产')

  const first = loader(null, () => new Promise(() => {}))
  first.loadAll()
  assert.equal(first.loading.value, true)
})

test('后台读取失败保留详情，新请求不会被旧响应覆盖', async () => {
  const existing = { asset: { id: 11 } }
  const failed = loader(existing, async () => { throw new Error('temporary') })
  await failed.loadAll()
  assert.equal(failed.detail.value, existing)
  assert.equal(failed.loading.value, false)
  assert.match(template, /v-else-if="error && !detail\?\.asset"/)
  const resolvers = []
  const state = loader(existing, () => new Promise(resolve => resolvers.push(resolve)))
  const oldRequest = state.loadAll()
  const newRequest = state.loadAll()
  resolvers[1]({ data: { asset: { id: 11, name: 'new' } } })
  await newRequest
  resolvers[0]({ data: { asset: { id: 11, name: 'old' } } })
  await oldRequest
  assert.equal(state.detail.value.asset.name, 'new')
})

test('用户页面不展示内部数据质量诊断', () => {
  assert.doesNotMatch(template, /datasetVersion/)
  assert.doesNotMatch(template, /qualityIssues/)
  assert.doesNotMatch(template, /qualityProviderWarnings/)
  assert.doesNotMatch(template, /dataQualityError/)
})

test('数据阻断和校验服务等待使用不同提示且绝不展示旧结论', () => {
  assert.match(template, /数据完整性校验未通过/)
  assert.match(template, /数据校验暂不可用/)
  assert.doesNotMatch(template, /使用可靠缓存|最近一次可靠结果/)
  assert.match(source, /qualityBlocked = computed\(\(\) => sourceStatus\.value\.dataState === 'BLOCKED'\)/)
  assert.match(source, /qualityWaiting = computed\(\(\) => sourceStatus\.value\.dataState === 'WAITING'\)/)
})

test('历史任务使用用户可理解的状态提示', () => {
  assert.match(template, /正在排队补齐历史数据/)
  assert.match(template, /正在补齐历史数据/)
  assert.match(template, /历史数据准备将自动重试/)
  assert.match(template, /历史数据已部分补齐/)
  assert.match(template, /历史数据准备未完成/)
  assert.doesNotMatch(template, /errorMessage/)
})

test('技术分析不再伪造金额和买卖数量', () => {
  assert.match(template, /专业走势研判/)
  assert.doesNotMatch(template, /数量参考|建议总预算|减仓数量参考/)
})

test('基金分类或专属策略不可用时明确停止给出操作建议', () => {
  assert.match(source, /fundAdviceUnavailable = computed/)
  assert.match(source, /FUND_CATEGORY_UNAVAILABLE/)
  assert.match(source, /暂无操作建议/)
  assert.match(source, /WAIT: '暂无操作建议'/)
  assert.doesNotMatch(template, /成立以来/)
  assert.match(source, /历史最大回撤/)
})

test('周期标签区分实际计算目标与用户设置范围', () => {
  assert.match(source, /value\?\.targetDays/)
  assert.match(source, /最近 \$\{value\.targetDays\} 个交易日（设置范围/)
})

test('基金短中长期分别展示各自窗口的回撤和基准相对指标', () => {
  assert.match(template, /周期最大回撤/)
  assert.match(template, /percent\(activeFundPeriod\.currentDrawdown/)
  assert.match(template, /percent\(activeFundPeriod\.maxDrawdown/)
  assert.match(template, /drawdownStatusLabel\(activeFundPeriod\.drawdownStatus/)
  assert.match(template, /基准收益/)
  assert.match(template, /跟踪差/)
  assert.match(template, /年化跟踪误差/)
  assert.match(template, /相关系数/)
  assert.match(template, /回归 Alpha/)
  assert.match(template, /R²/)
  assert.match(template, /信息比率 IR/)
  assert.match(template, /v-if="hasMetric\(activeFundPeriod\.benchmarkReturn\)"/)
})
