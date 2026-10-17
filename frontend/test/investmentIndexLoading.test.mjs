import assert from 'node:assert/strict'
import { before, after, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { createServer } from 'vite'

let server, browser, baseUrl
const indexes = [
  { indexCode: 'CN_INDEX:000300', name: '沪深300', market: 'CN', defaultItem: true, latestPrice: 4552.58, changePercent: 0.1, changeAmount: 4.5, dataTime: '2026-10-15 10:00:00' },
  { id: 9, indexCode: 'GLOBAL_INDEX:SPX', name: '标普500', market: 'US', defaultItem: false, latestPrice: 6480.2, changePercent: 0.31, changeAmount: 20, dataTime: '2026-10-14 16:00:00' },
  { indexCode: 'GLOBAL_INDEX:NDX', name: '纳斯达克100', market: 'US', defaultItem: true, latestPrice: 29143.33, changePercent: 0.23, changeAmount: 67, dataTime: '2026-10-14 16:00:00' },
]

before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    cacheDir: fileURLToPath(new URL('../node_modules/.vite-index-loading', import.meta.url)),
    server: { host: '127.0.0.1', port: 0, hmr: false },
  })
  await server.listen()
  baseUrl = `http://127.0.0.1:${server.httpServer.address().port}`
  browser = await chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : { channel: 'chrome' }),
  })
})

after(async () => { await browser?.close(); await server?.close() })

async function openPage(cached = [], controlledClock = false, delayReorder = false, now = new Date('2026-10-15T02:00:00Z')) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' })
  if (controlledClock) await page.clock.install({ time: now })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(items => {
    localStorage.setItem('token', 'index-layout-fixture')
    localStorage.setItem('user', JSON.stringify({ id: 7, username: 'fixture' }))
    sessionStorage.setItem('investment-index-watchlist:v1:7', JSON.stringify(items))
  }, cached)
  let heldRoute, reordered, orderRoute
  let requested
  const started = new Promise(resolve => { requested = resolve })
  let live = false
  let incoming = indexes
  let indexRequests = 0
  let assetRefreshes = 0
  await page.route(url => url.origin !== baseUrl, route => route.abort())
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/investment/assets/refresh') assetRefreshes++
    if (path === '/api/investment/indexes/watchlist' && request.method() === 'GET') indexRequests++
    if (path === '/api/investment/indexes/watchlist' && request.method() === 'GET' && !live) {
      heldRoute = route
      requested()
      return
    }
    if (path === '/api/investment/indexes/watchlist/order') {
      reordered = request.postDataJSON().indexCodes
      if (delayReorder) { orderRoute = route; return }
    }
    await route.fulfill({ json: { code: 200, data: path === '/api/investment/horizon-profile' ? { settings: [] }
      : path === '/api/investment/indexes/watchlist' ? incoming : [] } })
  })
  await page.goto(`${baseUrl}/stocks`, { waitUntil: 'domcontentloaded' })
  await started
  const strip = page.getByRole('region', { name: '市场指数横向列表' })
  return { page, strip, errors, get reordered() { return reordered }, get indexRequests() { return indexRequests },
    get assetRefreshes() { return assetRefreshes },
    rejectReorder: () => orderRoute.fulfill({ status: 500, json: { code: 500, message: '指数排序保存失败' } }),
    update: items => { incoming = items }, release: async (items = indexes) => {
    live = true
    await heldRoute.fulfill({ json: { code: 200, data: items } })
  } }
}

async function dragFirstAfterSecond(view) {
  await view.page.evaluate(() => document.fonts.ready)
  await view.page.waitForFunction(async () => {
    const strip = document.querySelector('[aria-label="市场指数横向列表"]')
    const first = strip?.querySelector('[data-cycle="0"]')
    if (!first || Math.abs(first.getBoundingClientRect().left - strip.getBoundingClientRect().left) > 1) return false
    const left = first.getBoundingClientRect().left
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    return Math.abs(first.getBoundingClientRect().left - left) < 1
  })
  const first = view.strip.locator('[data-cycle="0"]').nth(0)
  const a = await first.boundingBox()
  await view.page.mouse.move(a.x + a.width / 2, a.y + 45)
  await view.page.mouse.down()
  await view.page.waitForFunction(() => document.querySelector('[data-cycle="0"]')?.classList.contains('z-10'))
  const second = await view.strip.locator('[data-cycle="0"]').nth(1).boundingBox()
  await view.page.mouse.move(second.x + second.width / 2, second.y + 45, { steps: 5 })
}

test('指数冷启动不渲染灰色假卡片，行情卡片不展示技术时间', { timeout: 60000 }, async () => {
  const view = await openPage()
  try {
    assert.equal(await view.strip.locator('article').count(), 0)
    assert.equal(await view.page.getByRole('button', { name: '添加指数', exact: true }).isVisible(), true)
    await view.release(indexes.map((item, position) => position === 1
      ? { ...item, dataTime: null, fetchedAt: '2026-10-15T12:59:33.820296+00:00' } : item))
    await view.strip.locator('[data-cycle="0"]').first().waitFor()
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 3)
    assert.match(await view.strip.locator('[data-cycle="0"]').first().innerText(), /4,552\.58/)
    assert.doesNotMatch(await view.strip.innerText(), /行情截至|获取于|2026-10-(?:14|15)/)
    assert.deepEqual(view.errors, [])
  } finally { await view.page.close() }
})

test('指数静默更新会显示后台新行情，并合并尚未完成的请求', { timeout: 60000 }, async () => {
  const view = await openPage(indexes, true)
  try {
    await view.page.clock.runFor(9000)
    assert.equal(view.indexRequests, 1, '慢请求尚未完成时不能重复请求指数')
    const initial = view.page.waitForResponse(response => response.url().endsWith('/investment/indexes/watchlist'))
    await view.release()
    await initial
    view.update(indexes.map((item, position) => position === 0 ? { ...item, latestPrice: 4600 } : item))
    const updated = view.page.waitForResponse(response => response.url().endsWith('/investment/indexes/watchlist'), { timeout: 3000 })
    await view.page.clock.runFor(30000)
    await updated
    await view.page.waitForFunction(() => document.querySelector('[data-cycle="0"] .text-2xl')?.textContent === '4,600')
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 3)
    const focusRead = view.page.waitForResponse(response => new URL(response.url()).pathname === '/api/investment/assets')
    await view.page.evaluate(() => window.dispatchEvent(new Event('focus')))
    await focusRead
    assert.equal(view.assetRefreshes, 0, '列表重新获得焦点只读取已有数据，不能触发批量采集')
    assert.deepEqual(view.errors, [])
  } finally { await view.page.close() }
})

test('删除成功后迟到的旧指数请求不能恢复已删除自选', { timeout: 60000 }, async () => {
  const view = await openPage(indexes, true)
  try {
    await view.strip.locator('[data-cycle="0"]').getByRole('button', { name: '移除标普500', exact: true }).click()
    await view.page.waitForFunction(() => document.querySelectorAll('[data-cycle="0"]').length === 2)
    const initial = view.page.waitForResponse(response => response.url().endsWith('/investment/indexes/watchlist'))
    await view.release()
    const response = await initial
    await response.finished()
    await view.page.clock.runFor(50)
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 2)
    assert.equal(await view.strip.locator('[data-cycle="0"][data-index-code="GLOBAL_INDEX:SPX"]').count(), 0)
    const cache = await view.page.evaluate(() => JSON.parse(sessionStorage.getItem('investment-index-watchlist:v1:7')))
    assert.equal(cache.some(item => item.indexCode === 'GLOBAL_INDEX:SPX'), false)
  } finally { await view.page.close() }
})

test('闭市时保留指数缓存并停止周期报价请求', { timeout: 60000 }, async () => {
  const view = await openPage(indexes, true, false, new Date('2026-10-17T02:00:00Z'))
  try {
    await view.release()
    await view.page.clock.runFor(120000)
    assert.equal(view.indexRequests, 1)
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 3)
  } finally { await view.page.close() }
})

test('指数刷新期间保留已有卡片，拖动排序和移除仍操作真实自选', { timeout: 60000 }, async () => {
  const view = await openPage(indexes)
  try {
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 3)
    assert.match(await view.strip.locator('[data-cycle="0"]').first().innerText(), /4,552\.58/)
    await view.release()
    await dragFirstAfterSecond(view)
    const saved = view.page.waitForResponse(response => response.url().endsWith('/investment/indexes/watchlist/order'))
    await view.page.mouse.up()
    await saved
    assert.deepEqual(view.reordered, ['GLOBAL_INDEX:SPX', 'CN_INDEX:000300', 'GLOBAL_INDEX:NDX'])
    await view.strip.locator('[data-cycle="0"]').getByRole('button', { name: '移除标普500', exact: true }).click()
    await view.page.waitForFunction(() => document.querySelectorAll('[data-cycle="0"]').length === 2)
    assert.equal(await view.strip.locator('[data-cycle="0"][data-index-code="GLOBAL_INDEX:SPX"]').count(), 0)
    assert.deepEqual(view.errors, [])
  } finally { await view.page.close() }
})

test('较早的排序请求失败不能恢复随后成功删除的指数', { timeout: 60000 }, async () => {
  const view = await openPage(indexes, false, true)
  try {
    await dragFirstAfterSecond(view)
    const started = view.page.waitForRequest(request => request.url().endsWith('/investment/indexes/watchlist/order'))
    await view.page.mouse.up()
    await started
    await view.strip.locator('[data-cycle="0"]').getByRole('button', { name: '移除标普500', exact: true }).click()
    await view.page.waitForFunction(() => document.querySelectorAll('[data-cycle="0"]').length === 2)
    await view.rejectReorder()
    await view.page.getByText('指数排序保存失败', { exact: true }).first().waitFor()
    assert.equal(await view.strip.locator('[data-cycle="0"]').count(), 2)
    assert.equal(await view.strip.locator('[data-cycle="0"][data-index-code="GLOBAL_INDEX:SPX"]').count(), 0)
  } finally { await view.page.close() }
})
