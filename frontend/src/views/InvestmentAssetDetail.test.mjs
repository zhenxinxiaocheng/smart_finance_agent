import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'

const source = readFileSync(new URL('./InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const template = source.match(/<template>[\s\S]*<\/template>/)?.[0] || ''

function loader(existing, request) {
  const context = vm.createContext({
    detail: { value: existing }, loading: { value: false },
    error: { value: '' }, detailRequestToken: 0, route: { params: { assetId: '11' } },
    isCurrentAsset: id => String(id) === '11', getInvestmentAssetDetailAPI: request
  })
  const start = source.indexOf('async function loadAll(')
  const end = source.indexOf('\nasync function syncLatest(', start)
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

function analysisNotifications(t) {
  const messages = []
  const detail = ref(null)
  const route = reactive({ params: { assetId: '11' } })
  const scope = effectScope()
  t.after(() => scope.stop())
  const context = vm.createContext({
    computed, watch, detail, route,
    sourceStatus: computed(() => detail.value?.sourceStatus || {}),
    feedback: { info: message => messages.push(message) },
    startInvestmentRealtimePolling: () => () => {},
    detailRefreshRunner: () => {}, loadAll: async () => {}
  })
  const pendingStart = source.indexOf('const pendingAnalysis = computed(')
  const watchersStart = source.indexOf('let stopDetailUpdates = null')
  scope.run(() => vm.runInContext(
    source.slice(pendingStart, source.indexOf('const activeFundPeriod', pendingStart))
      + source.slice(watchersStart, source.indexOf('watch(horizonOptions', watchersStart)), context))
  return { messages, route, async receive(state) {
    detail.value = { asset: { id: route.params.assetId }, sourceStatus: { dataState: state } }
    await nextTick()
  } }
}

test('同一资产只提醒一次，轮询及准备状态变化不会重复弹出', async t => {
  const state = analysisNotifications(t)
  for (const status of ['PREPARING', 'PREPARING', 'WAITING', 'BLOCKED', 'STABLE_CACHE', 'READY', 'BLOCKED']) {
    await state.receive(status)
  }
  assert.equal(state.messages.length, 1)
  assert.match(state.messages[0], /分析.*后台.*自动显示/)
})

test('已就绪资产不提醒，切换到另一资产后独立提醒一次', async t => {
  const state = analysisNotifications(t)
  await state.receive('READY')
  assert.deepEqual(state.messages, [])
  await state.receive('WAITING')
  assert.equal(state.messages.length, 1)
  state.route.params.assetId = '13'
  await nextTick()
  await state.receive('PREPARING')
  await state.receive('BLOCKED')
  assert.equal(state.messages.length, 2)
})

test('已有行情等待分析时不误报数据不足', () => {
  const activeAnalysis = ref({ status: 'PREPARING' })
  const context = vm.createContext({ computed, activeAnalysis, fundAdviceUnavailable: ref(false), isFund: ref(true), fundActionLabel: () => '暂无操作建议' })
  const start = source.indexOf('const activeHeadline = computed(')
  const end = source.indexOf('const activePeriodText', start)
  vm.runInContext(source.slice(start, end) + '\nglobalThis.headline = activeHeadline', context)
  assert.equal(context.headline.value, '—')
  activeAnalysis.value = { status: 'INSUFFICIENT' }
  assert.equal(context.headline.value, '数据不足')
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
