import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { investmentHelpText } from '../src/lib/investmentHelpText.js'

const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../src/views/InvestmentAssetDetail.vue', import.meta.url), 'utf8')
const tooltipSource = readFileSync(new URL('../src/components/ui/tooltip/InfoTooltip.vue', import.meta.url), 'utf8')
const horizonDialogSource = readFileSync(new URL('../src/components/investment/HorizonProfileDialog.vue', import.meta.url), 'utf8')
const investmentApiSource = readFileSync(new URL('../src/api/investment.js', import.meta.url), 'utf8')

test('投资详情页使用动态路由和主要分析分区', () => {
  assert.match(routerSource, /path:\s*'stocks\/:assetId'/)
  assert.match(routerSource, /name:\s*'InvestmentAssetDetail'/)
  assert.match(pageSource, /InvestmentKlineChart/)
  assert.match(pageSource, /专业走势研判/)
  assert.match(pageSource, /多周期分析/)
  assert.match(pageSource, /财务警告/)
  assert.doesNotMatch(pageSource, /查看详细分析|历史回测与 AI 解读/)
})

test('分析周期来自后端画像且单资产设置可恢复为全局配置', () => {
  assert.match(pageSource, /detail\.value\?\.analysisPreference/)
  assert.match(pageSource, /horizonOptions = computed/)
  assert.doesNotMatch(pageSource, /const horizonOptions = \[/)
  assert.doesNotMatch(pageSource, /shortMinDays|mediumMinDays|longMinDays/)
  assert.match(pageSource, /clearInvestmentAssetHorizonOverrideAPI/)
  assert.match(horizonDialogSource, /恢复全局设置/)
  assert.match(horizonDialogSource, /v-for="\(item, index\) in draft\.settings"/)
  assert.doesNotMatch(horizonDialogSource, /5,?\s*20|20,?\s*120|120,?\s*500/)
  assert.match(investmentApiSource, /\/investment\/horizon-profile/)
})

test('数据不足的周期不展示伪造评分', () => {
  assert.match(pageSource, /item\.data\.status === 'INSUFFICIENT'/)
  assert.match(pageSource, /历史数据不足，暂不判断走势/)
})

test('风险警告与技术结果在页面上分区呈现', () => {
  assert.match(pageSource, /technicalAnalysis/)
  assert.match(pageSource, /financialWarnings/)
  assert.doesNotMatch(pageSource, /affectsTechnicalAnalysis/)
})

test('投资详情页不提供全局总资产入口', () => {
  assert.doesNotMatch(pageSource, /openWealthDialog|wealthOpen|updateWealthBaselineAPI/)
  assert.match(pageSource, /WEALTH_NOT_INITIALIZED: '现金基准未初始化'/)
})

test('股票中性结论无确认歧义且基金使用专用动作布局', () => {
  assert.doesNotMatch(pageSource, /等待确认/)
  assert.match(pageSource, /近期表现与风险概览/)
  assert.match(pageSource, /v-if="asset\.productType === 'MUTUAL_FUND'" class="space-y-2\.5"/)
  assert.match(pageSource, /v-if="asset\.productType !== 'MUTUAL_FUND'" class="[^"]*space-y-3"/)
})

test('平板改为上下布局且手机端压缩关键指标和图表高度', { timeout: 60000 }, async () => {
  const { chromium } = await import('playwright-core')
  const { createServer } = await import('vite')
  const server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    cacheDir: fileURLToPath(new URL('../node_modules/.vite-investment-detail-layout', import.meta.url)),
    server: { host: '127.0.0.1', port: 0, hmr: false },
  })
  let browser
  try {
    await server.listen()
    browser = await chromium.launch({
      headless: true,
      ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH
        ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH }
        : { channel: 'chrome' }),
    })
    const page = await browser.newPage({ reducedMotion: 'reduce' })
    await page.clock.install({ time: new Date('2026-10-17T02:00:00Z') })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      localStorage.setItem('token', 'layout-fixture')
      localStorage.setItem('user', JSON.stringify({ id: 1, username: 'layout-fixture' }))
      if (!localStorage.getItem('sfa-density')) localStorage.setItem('sfa-density', 'comfortable')
    })
    const detail = {
      asset: { id: 1, name: '布局回归测试股票', code: '600000', productType: 'STOCK', market: 'SSE', latestPrice: 10 },
      quoteSeries: Array.from({ length: 20 }, (_, index) => ({
        date: `2026-01-${String(index + 5).padStart(2, '0')}`,
        open: 10 + index / 10, close: 10.05 + index / 10,
        high: 10.1 + index / 10, low: 9.9 + index / 10, volume: 1000,
      })),
      analysisPreference: { settings: [{ code: 'SHORT', displayName: '短期', primary: true }] },
      technicalAnalysis: { status: 'READY', horizons: { SHORT: { status: 'READY' } } },
      sourceStatus: { dataState: 'READY' },
    }
    // Match the API prefix only; /src/api/*.js must remain real application modules.
    let detailRequests = 0
    let quoteSyncRequests = 0
    await page.route(url => url.pathname.startsWith('/api/'), route => {
      const isDetail = new URL(route.request().url()).pathname === '/api/investment/assets/1/detail'
      if (isDetail) detailRequests++
      if (route.request().method() === 'POST' && new URL(route.request().url()).pathname.endsWith('/sync')) quoteSyncRequests++
      return route.fulfill({ json: { code: 200, data: isDetail ? detail : [] } })
    })
    const url = `http://127.0.0.1:${server.httpServer.address().port}/stocks/1`
    // External fonts must not make a local layout regression depend on the network.
    await page.route(remote => remote.origin !== new URL(url).origin, route => route.abort())
    async function measure(width) {
      await page.setViewportSize({ width, height: 1000 })
      await page.goto(url, { waitUntil: 'domcontentloaded' })
      await page.locator('.investment-chart canvas').waitFor()
      return page.evaluate(() => {
        const chart = document.querySelector('.investment-chart')
        const card = chart.closest('[data-slot="card"]')
        const metric = document.querySelector('.app-page > section .tabular-nums')
        const rect = element => {
          const { x, y, width, height, right, bottom } = element.getBoundingClientRect()
          return { x, y, width, height, right, bottom }
        }
        return {
          chart: rect(chart), card: rect(card), panel: rect(card.nextElementSibling),
          fontSize: parseFloat(getComputedStyle(metric).fontSize),
          padding: parseFloat(getComputedStyle(card).paddingTop),
          overflow: document.querySelector('.app-page').scrollWidth - document.querySelector('.app-page').clientWidth,
        }
      })
    }
    const desktop = await measure(1440)
    const header = page.locator('.app-page header')
    for (const name of ['数据管理', '同步最新行情', '全量修复历史数据']) {
      assert.equal(await page.getByRole('button', { name, exact: true }).count(), 0, `详情页不再提供${name}操作`)
    }
    for (const name of ['分析周期', '编辑持仓', '策略工作台']) {
      assert.equal(await header.getByRole('button', { name, exact: true }).isVisible(), true, `${name}入口应保留`)
    }
    assert.ok(desktop.card.x >= 0 && desktop.panel.right <= 1441, '桌面图表和侧栏应位于视口内')
    assert.ok(Math.abs(desktop.card.y - desktop.panel.y) < 1, '桌面图表与研判面板应并排对齐')
    assert.ok(desktop.panel.x >= desktop.card.right && desktop.card.width > desktop.panel.width * 2, '桌面应保留主图与侧栏的宽度层级')
    for (const width of [1024, 768, 375]) {
      const layout = await measure(width)
      assert.ok(layout.panel.y >= layout.card.bottom, `${width}px 下研判面板应位于图表下方`)
      assert.ok(Math.abs(layout.panel.x - layout.card.x) < 1 && Math.abs(layout.panel.width - layout.card.width) < 1, '上下布局应等宽对齐')
      assert.ok(layout.overflow <= 1, `${width}px 下详情内容不应横向溢出`)
      assert.ok(layout.card.x >= 0 && layout.panel.x >= 0 && layout.card.right <= width + 1 && layout.panel.right <= width + 1, `${width}px 下卡片不能超出视口后被裁切`)
      assert.ok(layout.chart.height >= 360 && layout.chart.height < desktop.chart.height, '窄容器图表应缩小并保留可读高度')
      if (width === 375) assert.ok(layout.fontSize >= 14 && layout.fontSize < desktop.fontSize, '手机关键指标应缩小且保持可读')
    }
    await page.evaluate(() => localStorage.setItem('sfa-density', 'compact'))
    const compact = await measure(1440)
    assert.ok(compact.padding > 0 && compact.padding < desktop.padding, '刷新后卡片内边距应遵循紧凑密度，不能被固定 py 类覆盖')

    detail.asset.market = 'NASDAQ'
    detail.asset.quoteFrequency = 'DAILY'
    detail.technicalAnalysis = { status: 'PREPARING' }
    detail.sourceStatus = { dataState: 'PREPARING', analysisJobStatus: 'RUNNING' }
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.getByText('分析待更新', { exact: true }).first().waitFor()
    assert.doesNotMatch(await page.locator('.app-page').innerText(), /等待足够历史数据/)
    const initialRequests = detailRequests
    await page.clock.runFor(5000)
    assert.equal(detailRequests, initialRequests, '分析结果不应以报价频率或旧的三秒间隔重复请求')
    const updated = page.waitForResponse(response => response.url().endsWith('/assets/1/detail'))
    await page.clock.runFor(5000)
    await updated
    assert.equal(detailRequests, initialRequests + 1)
    detail.technicalAnalysis = { status: 'UNAVAILABLE' }
    detail.sourceStatus = { dataState: 'UNAVAILABLE', analysisJobStatus: 'FAILED' }
    const failed = page.waitForResponse(response => response.url().endsWith('/assets/1/detail'))
    await page.clock.runFor(10000)
    await failed
    await page.getByText('分析暂不可用', { exact: true }).first().waitFor()
    const stoppedRequests = detailRequests
    const nextRead = page.waitForResponse(response => response.url().endsWith('/assets/1/detail'))
    await page.clock.runFor(10000)
    await nextRead
    assert.equal(detailRequests, stoppedRequests + 1, '失败后停止十秒加速检查，三十秒仍读取已有结果')
    await page.clock.runFor(9000)
    assert.equal(detailRequests, stoppedRequests + 1, '终止任务不能继续使用十秒频率')
    detail.technicalAnalysis = { status: 'READY', horizons: { SHORT: {
      status: 'READY', outlook: { direction: 'BULLISH', confidence: 'MEDIUM' },
    } } }
    detail.sourceStatus = { dataState: 'READY', analysisJobStatus: 'SUCCEEDED' }
    const recovered = page.waitForResponse(response => response.url().endsWith('/assets/1/detail'))
    await page.clock.runFor(21000)
    await recovered
    await page.getByText('分析暂不可用', { exact: true }).first().waitFor({ state: 'hidden' })
    assert.equal(detailRequests, stoppedRequests + 2, '无需重新打开页面即可看到后台完成的结果')
    assert.equal(quoteSyncRequests, 0, '日频资产和休市读取不能触发采集')
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    const hiddenRequests = detailRequests
    await page.clock.runFor(60000)
    assert.equal(detailRequests, hiddenRequests, '页面隐藏时暂停读取')
    const visibleRead = page.waitForResponse(response => response.url().endsWith('/assets/1/detail'))
    await page.evaluate(() => {
      delete document.hidden
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await visibleRead
    assert.equal(detailRequests, hiddenRequests + 1, '返回可见页面后立即读取已有结果')
    assert.equal(await page.locator('.investment-chart canvas').count() > 0, true, '更新失败保留已加载图表')
    assert.deepEqual(errors, [], '布局与图表渲染不应出现运行时错误')
  } finally {
    await browser?.close()
    await server.close()
  }
})

test('复杂模块提供可悬停和键盘访问的问号说明', () => {
  assert.match(pageSource, /InfoTooltip/)
  assert.match(pageSource, /helpText\.technicalOutlook/)
  assert.match(tooltipSource, /TooltipTrigger/)
  assert.match(tooltipSource, /aria-label/)
  assert.match(tooltipSource, /CircleHelp/)
})

test('只解释专业指标且说明用户如何阅读结果', () => {
  assert.doesNotMatch(pageSource, /helpText\.aiExplanation/)
  assert.doesNotMatch(pageSource, /helpText\.financialWarnings/)
  assert.match(investmentHelpText.technicalOutlook, /趋势、动量、量价、波动和价格结构/)
  assert.match(investmentHelpText.priceZones, /买入\/加仓/)
  assert.doesNotMatch(JSON.stringify(investmentHelpText), /方便理解|用通俗中文解释|不保证未来一定上涨/)
})

test('基金收益窗口由策略结果动态渲染', () => {
  assert.match(pageSource, /activeFundPeriod/)
  assert.match(pageSource, /fundFullHistoryMetrics/)
  assert.match(pageSource, /周期最大回撤/)
  assert.match(pageSource, /历史最大回撤/)
  assert.doesNotMatch(pageSource, /成立以来/)
  assert.match(pageSource, /当前回撤/)
  assert.match(pageSource, /drawdownStatusLabel/)
  assert.doesNotMatch(pageSource, /fundReturnMetrics|oneMonthReturn|threeMonthReturn|oneYearReturn/)
})

test('基本面数据要求由后端结果动态展示', () => {
  assert.match(pageSource, /fundamental\.reason/)
  assert.doesNotMatch(pageSource, /至少需要三期数据和四个有效维度/)
})

test('页面和帮助文案不向用户展示分析过程', () => {
  assert.doesNotMatch(pageSource, /结论来自|基金动作基于|价位来自行情/)
  assert.doesNotMatch(JSON.stringify(investmentHelpText), /评分依据|由当前投资账户.*计算|在历史行情中查找|分别计算/)
})

test('结论卡不重复解释显而易见的操作', () => {
  assert.doesNotMatch(pageSource, /现在怎么做|conclusionAction/)
})

test('技术走势是主结论且旧伪数量模块已删除', () => {
  assert.match(pageSource, /activeOutlook/)
  assert.match(pageSource, /outlookDirectionLabel/)
  assert.match(pageSource, /走势失效条件/)
  assert.doesNotMatch(pageSource, /数量参考|personalized|quantityState|buildQuantityReferenceState/)
  assert.doesNotMatch(pageSource, /查看详细分析/)
})

test('当前周期的关键价位和主要结论来自同一技术走势规则', () => {
  assert.match(pageSource, /:levels="activeLevels"/)
  assert.match(pageSource, /activeAnalysis\.value\.levels/)
  assert.match(pageSource, /activeAnalysis\.value\.actionZones/)
  assert.match(pageSource, /activeAnalysis\.value\.outlook/)
  assert.doesNotMatch(pageSource, /label:\s*'技术评分'.*topMetrics/)
})

test('详情页自动分析且不提供手动刷新分析按钮', () => {
  assert.doesNotMatch(pageSource, /刷新分析/)
  assert.doesNotMatch(pageSource, /refreshInvestmentAssetAnalysisAPI/)
  assert.doesNotMatch(pageSource, /async function refreshAnalysis/)
})

test('详情页只读：不轮询历史任务，也不渲染任务状态文案', () => {
  assert.doesNotMatch(pageSource, /getInvestmentHistoryJobAPI/)
  assert.doesNotMatch(pageSource, /createHistoryJobPollingController/)
  assert.doesNotMatch(pageSource, /historyJob/)
  assert.doesNotMatch(pageSource, /backgroundRefreshing/)
  assert.doesNotMatch(pageSource, /cachedAnalysisPending/)
  assert.doesNotMatch(pageSource, /数据暂不可用/)
})

test('页面优先展示当前分析数据截至日期', () => {
  assert.match(pageSource, /当前分析数据截至/)
  assert.match(pageSource, /analysisAsOfText/)
})

test('分析更新只显示页内提示且详情接口抑制原始错误弹窗', () => {
  assert.doesNotMatch(pageSource, /数据完整性校验未通过|数据校验暂不可用|数据已阻断|等待重新校验/)
  assert.match(pageSource, /v-if="analysisUpdateNotice" role="status"/)
  assert.match(pageSource, /analysisJobStatus/)
  assert.match(pageSource, /detailRefreshRunner\(false, true\), 10000/)
  assert.doesNotMatch(pageSource, /feedback.info\('分析正在后台更新/)
  assert.match(investmentApiSource, /getInvestmentAssetDetailAPI[^\n]*silentFeedback: true/)
  assert.match(investmentApiSource, /syncInvestmentAssetAPI[^\n]*silentFeedback: true/)
  assert.match(pageSource, /analysisAsOf = computed\(\(\) => sourceStatus\.value\.quoteDate/)
})
