import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { createServer } from 'vite'

// Uses an installed Chrome by default. CI can supply PLAYWRIGHT_EXECUTABLE_PATH.
let browser
let server
let baseUrl

before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('.', import.meta.url)),
    cacheDir: fileURLToPath(new URL('./node_modules/.vite-auth-layout', import.meta.url)),
    server: { host: '127.0.0.1', port: 0, hmr: false }
  })
  await server.listen()
  baseUrl = `http://127.0.0.1:${server.httpServer.address().port}`
  browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH }
      : { channel: 'chrome' })
  })
})

after(async () => {
  await browser?.close()
  await server?.close()
})

async function measure(page) {
  await page.locator('.auth-card').waitFor({ state: 'visible' })
  return page.evaluate(() => {
    const rect = selector => {
      const r = document.querySelector(selector).getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
    }
    return {
      width: innerWidth,
      hero: rect('.auth-hero-body'),
      card: rect('.auth-card'),
      input: rect('#username'),
      heading: parseFloat(getComputedStyle(document.querySelector('.auth-hero h2')).fontSize),
      heroVisible: getComputedStyle(document.querySelector('.auth-hero')).display !== 'none',
      scrollWidth: document.documentElement.scrollWidth
    }
  })
}

for (const route of ['/login', '/register']) {
  test(`${route}: 大屏内容和控件随视口增大，保持两列靠近`, async () => {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' })
    try {
      // Exercise fallback fonts without depending on the external font service.
      await page.route(/^https:\/\/fonts\.(googleapis|gstatic)\.com\//, request => request.abort())
      await page.addInitScript(() => {
        localStorage.setItem('sfa-mode', 'dark')
        localStorage.setItem('sfa-theme-color', 'rose')
        localStorage.setItem('sfa-density', 'compact')
      })
      await page.goto(`${baseUrl}${route}`)
      const desktop = await measure(page)
      await page.setViewportSize({ width: 2560, height: 1360 })
      const wide = await measure(page)
      assert.ok(wide.card.width >= desktop.card.width * 1.2, '大屏表单应增大至少 20%')
      assert.ok(wide.card.width >= 540, '2560px 视口表单不能仍只有 400px')
      assert.ok(wide.heading >= 48, '大屏标题需要同步增大')
      assert.ok(wide.input.height >= 48, '大屏输入框需要同步增大')
      assert.ok(wide.card.right - wide.hero.x >= wide.width * 0.65, '主体应覆盖大屏至少 65% 的宽度')
      assert.ok(wide.card.x - wide.hero.right <= wide.width * 0.16, '扩大宽度不能只拉开两列距离')
      assert.equal(wide.scrollWidth, wide.width)
      assert.ok(wide.card.bottom <= 1360)
    } finally {
      await page.close()
    }
  })

  test(`${route}: 手机、平板和低高度窗口无横向溢出，表单可操作`, async () => {
    const page = await browser.newPage({ reducedMotion: 'reduce' })
    try {
      await page.route(/^https:\/\/fonts\.(googleapis|gstatic)\.com\//, request => request.abort())
      for (const viewport of [
        { width: 375, height: 812 },
        { width: 768, height: 1024 },
        { width: 1024, height: 768 },
        { width: 1440, height: 520 }
      ]) {
        await page.setViewportSize(viewport)
        await page.goto(`${baseUrl}${route}`)
        const layout = await measure(page)
        assert.equal(layout.scrollWidth, viewport.width, `${viewport.width}px 不应横向滚动`)
        assert.ok(layout.card.x >= 16 && layout.card.right <= viewport.width - 16)
        assert.ok(layout.card.y >= 16, '短窗口不能把表单顶部裁掉')
        assert.equal(layout.heroVisible, viewport.width >= 1024)
        await page.locator('.auth-submit').click()
        assert.equal(await page.locator('#username').getAttribute('aria-invalid'), 'true')
        await page.locator('#password').fill('layout-check')
        await page.locator('#password').locator('..').getByRole('button', { name: '显示密码', exact: true }).click()
        assert.equal(await page.locator('#password').getAttribute('type'), 'text')
        await page.locator('#password').locator('..').getByRole('button', { name: '隐藏密码', exact: true }).click()
        assert.equal(await page.locator('#password').getAttribute('type'), 'password')
      }
      const nextRoute = route === '/login' ? '/register' : '/login'
      await page.locator(`a[href="${nextRoute}"]`).click()
      await page.waitForURL(`${baseUrl}${nextRoute}`)
      await page.reload()
      assert.equal((await measure(page)).scrollWidth, 1440)
    } finally {
      await page.close()
    }
  })
}
