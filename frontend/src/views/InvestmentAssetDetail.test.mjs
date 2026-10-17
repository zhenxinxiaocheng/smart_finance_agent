import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { createPrioritizedRefreshRunner, isInvestmentQuoteSessionActive } from '../lib/investmentRealtime.js'

const source = readFileSync(new URL('./InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const template = source.match(/<template>[\s\S]*<\/template>/)?.[0] || ''

function loader(existing, request) {
  const context = vm.createContext({
    detail: { value: existing }, loading: { value: false },
    error: { value: '' }, detailRequestToken: 0, route: { params: { assetId: '11' } },
    isCurrentAsset: id => String(id) === '11', getInvestmentAssetDetailAPI: request
  })
  const start = source.indexOf('async function loadAll(')
  const end = source.indexOf('\nasync function reloadAfterHoldingSaved(', start)
  assert.ok(start >= 0 && end > start, '详情加载函数边界必须存在')
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
  assert.equal(refresh.detail.value, existing)
  finish({ data: { asset: { id: 11, name: '更新后的资产' } } })
  await done
  assert.equal(refresh.loading.value, false)
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

test('内部阻断和服务等待不在标题、页面和图表重复提示', () => {
  assert.doesNotMatch(template, /数据完整性校验未通过|数据校验暂不可用/)
  assert.doesNotMatch(template, /sourceLabel|history-warning|分析更新中<\/AlertTitle>|历史数据准备中<\/AlertTitle>/)
  assert.doesNotMatch(template, /使用可靠缓存|最近一次可靠结果/)
})

test('历史准备自动更新详情，内部任务状态不直接呈现', () => {
  assert.match(source, /watch\(pendingAnalysis/)
  assert.match(source, /startInvestmentRealtimePolling/)
  assert.doesNotMatch(template, /historyJob\.status|historyJob\.errorMessage/)
  assert.doesNotMatch(template, /errorMessage/)
})

test('删除手动数据入口后，自动同步仍复用缓存且活动分析只读取详情', async () => {
  const synced = []
  const reads = []
  const asset = ref({ market: 'SSE', quoteFrequency: 'REALTIME' })
  const pendingAnalysis = ref(false)
  let quoteError = false
  const context = vm.createContext({
    route: { params: { assetId: '11' } }, loading: ref(false), savingPreference: ref(false),
    asset, pendingAnalysis, createPrioritizedRefreshRunner,
    isInvestmentQuoteSessionActive: items => isInvestmentQuoteSessionActive(items, new Date('2026-10-19T02:00:00Z')),
    syncInvestmentAssetAPI: async (...args) => {
      synced.push(args)
      if (quoteError) throw new Error('报价暂不可用')
    },
    loadAll: async (...args) => { reads.push(args) },
  })
  const start = source.indexOf('let componentDisposed = false')
  const end = source.indexOf('let stopDetailUpdates = null', start)
  assert.ok(start >= 0 && end > start)
  vm.runInContext(source.slice(start, end) + '\nglobalThis.refreshDetail = detailRefreshRunner', context)

  await context.refreshDetail(false, true)
  assert.deepEqual(reads, [])
  await context.refreshDetail(true, true)
  assert.deepEqual(synced, [['11', false]], '自动行情同步不能变成强制采集')
  assert.deepEqual(reads, [['11', true]])
  pendingAnalysis.value = true
  await context.refreshDetail(false, true)
  assert.deepEqual(synced, [['11', false]], '分析状态更新不应额外采集行情')
  assert.deepEqual(reads, [['11', true], ['11', true]])
  await context.refreshDetail(true, true)
  assert.equal(reads.length, 2, '活动任务由十秒检查读取，避免与三十秒读取重复')
  pendingAnalysis.value = false
  asset.value.quoteFrequency = 'DAILY'
  await context.refreshDetail(true, true)
  assert.equal(synced.length, 2, '日频行情继续遵守原有刷新规则')
  assert.equal(reads.length, 3, '日频资产也必须读取后台更新的分析结果')
  asset.value.quoteFrequency = 'REALTIME'
  asset.value.market = 'NASDAQ'
  await context.refreshDetail(true, true)
  assert.equal(synced.length, 2, '美股休市时不能请求实时报价')
  assert.equal(reads.length, 4, '休市不能阻止读取后台分析结果')
  asset.value.market = 'SSE'
  quoteError = true
  await context.refreshDetail(true, true)
  assert.equal(reads.length, 5, '报价请求失败不能阻止读取已保存的分析结果')
})

function analysisUpdates(t) {
  const messages = []
  const timers = []
  const detail = ref(null)
  const route = reactive({ params: { assetId: '11' } })
  const scope = effectScope()
  t.after(() => scope.stop())
  const context = vm.createContext({
    computed, watch, detail, route, onMounted: () => {},
    sourceStatus: computed(() => detail.value?.sourceStatus || {}),
    feedback: { info: message => messages.push(message) },
    startInvestmentRealtimePolling: (_callback, interval) => {
      const timer = { interval, stopped: false }
      timers.push(timer)
      return () => { timer.stopped = true }
    },
    detailRefreshRunner: () => {}, loadAll: async () => {}
  })
  const pendingStart = source.indexOf('const pendingAnalysis = computed(')
  const watchersStart = source.indexOf('let stopDetailUpdates = null')
  scope.run(() => vm.runInContext(
    source.slice(pendingStart, source.indexOf('const activeFundPeriod', pendingStart))
      + source.slice(watchersStart, source.indexOf('watch(horizonOptions', watchersStart)), context))
  return { messages, timers, route, get notice() { return vm.runInContext('analysisUpdateNotice.value', context) },
    async receive(dataState, analysisJobStatus) {
    detail.value = { asset: { id: route.params.assetId }, sourceStatus: { dataState, analysisJobStatus } }
    await nextTick()
  } }
}

test('仅真实活动任务每十秒检查分析，状态变化不重复启动或弹窗', async t => {
  const state = analysisUpdates(t)
  for (const status of ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'RUNNING']) {
    await state.receive('STABLE_CACHE', status)
  }
  assert.equal(state.timers.length, 1)
  assert.equal(state.timers[0].interval, 10000)
  assert.equal(state.timers[0].stopped, false)
  assert.match(state.notice, /当前内容已保留/)
  await state.receive('STABLE_CACHE', 'FAILED')
  assert.equal(state.timers[0].stopped, true)
  assert.match(state.notice, /分析更新未完成/)
  assert.deepEqual(state.messages, [])
})

test('无活动任务不启动十秒检查，切换资产独立检查并在完成后停止', async t => {
  const state = analysisUpdates(t)
  for (const status of ['READY', 'PREPARING', 'WAITING', 'BLOCKED']) await state.receive(status)
  assert.equal(state.timers.length, 0)
  state.route.params.assetId = '13'
  await nextTick()
  await state.receive('PREPARING', 'QUEUED')
  assert.equal(state.timers.length, 1)
  await state.receive('READY', 'SUCCEEDED')
  assert.equal(state.timers[0].stopped, true)
  await state.receive('READY', 'SUCCEEDED')
  assert.equal(state.timers.length, 1)
  assert.deepEqual(state.messages, [])
})

test('已有行情等待分析时不误报数据不足', () => {
  const activeAnalysis = ref({ status: 'PREPARING' })
  const context = vm.createContext({ computed, activeAnalysis, fundAdviceUnavailable: ref(false), isFund: ref(true), fundActionLabel: () => '暂无操作建议' })
  const start = source.indexOf('const activeHeadline = computed(')
  const end = source.indexOf('const activePeriodText', start)
  vm.runInContext(source.slice(start, end) + '\nglobalThis.headline = activeHeadline', context)
  assert.equal(context.headline.value, '—')
  activeAnalysis.value = { status: 'INSUFFICIENT' }
  assert.equal(context.headline.value, '历史样本不足')
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


test('失败状态和样本不足分别显示真实原因', () => {
  const start = source.indexOf('const activeHeadline = computed(')
  const end = source.indexOf('const activePeriodText', start)
  for (const [status, expected] of [['BLOCKED', '数据校验未通过'], ['UNAVAILABLE', '分析暂不可用'], ['INSUFFICIENT', '历史样本不足']]) {
    const context = vm.createContext({ computed, activeAnalysis: { value: { status } }, fundAdviceUnavailable: { value: false } })
    vm.runInContext(source.slice(start, end), context)
    assert.equal(vm.runInContext('activeHeadline.value', context), expected)
  }
})
