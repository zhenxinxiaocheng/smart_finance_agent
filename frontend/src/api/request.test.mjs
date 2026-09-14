import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

let server, request, quant, events, expired
before(async () => {
  globalThis.window = { dispatchEvent: event => events.push(event.detail) }
  globalThis.localStorage = { getItem: () => 'test-token' }
  globalThis.__testSessionExpiry = () => { expired++ }
  const root = fileURLToPath(new URL('../../', import.meta.url))
  server = await createServer({ root, configFile: false,
    plugins: [{ name: 'session-navigation-boundary', enforce: 'pre',
      resolveId(id) { if (id === '@/lib/sessionExpiry' || /\/lib\/sessionExpiry(?:\.js)?$/.test(id)) return '\0test-session' },
      load(id) { if (id === '\0test-session') return 'export const resetSessionExpiryHandling = () => {}; export const handleSessionExpired = async () => globalThis.__testSessionExpiry()' } }],
    resolve: { alias: { '@': `${root}/src` } }, optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  request = (await server.ssrLoadModule('/src/api/request.js')).default
  quant = (await server.ssrLoadModule('/src/api/quantWorkbench.js')).quant
})
after(async () => { await server?.close(); delete globalThis.window; delete globalThis.localStorage; delete globalThis.__testSessionExpiry })

test('normal and silent GET share auth, timeout, URL and data parsing', async () => {
  events = []; expired = 0
  const calls = [], payload = { id: 'a/b', progress: null }
  request.defaults.adapter = async config => { calls.push(config); return { config, status: 200, data: { code: 200, data: payload } } }
  for (const silent of [false, true]) assert.deepEqual(await quant.experiments.get('a/b', { silent }), payload)
  for (const config of calls) {
    assert.equal(config.url, '/quant/v2/experiments/a%2Fb')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.timeout, 30000)
    assert.equal(config.headers.Authorization, 'Bearer test-token')
  }
  assert.notEqual(calls[0].silentFeedback, true)
  assert.equal(calls[1].silentFeedback, true)
  assert.equal(events.length, 0)
})

test('silentFeedback changes only toast on transport errors, including 401', async () => {
  for (const status of [undefined, 400, 401, 403, 404, 408, 429, 500, 503]) {
    for (const silentFeedback of [false, true]) {
      events = []; expired = 0
      const failure = Object.assign(new Error('upstream'), { code: status ? 'ERR_BAD_RESPONSE' : 'ECONNABORTED', response: status ? { status, data: { code: status, message: '安全提示', data: { reasonCode: 'REASON_CODE' } } } : undefined })
      request.defaults.adapter = async config => { failure.config = config; throw failure }
      await assert.rejects(request.get('/test', { silentFeedback }), caught => caught === failure)
      assert.equal(failure.response?.status, status)
      if (status) assert.equal(failure.response.data.data.reasonCode, 'REASON_CODE')
      assert.equal(expired, status === 401 ? 1 : 0)
      assert.equal(events.length, status === 401 || silentFeedback ? 0 : 1)
      assert.equal(failure.__feedbackShown === true, status !== 401 && !silentFeedback)
      if (!status && !silentFeedback) assert.equal(events[0].type, 'warning')
    }
  }
})

test('business failure preserves structured response equally in both feedback modes', async () => {
  for (const code of [400, 401]) for (const silentFeedback of [false, true]) {
    events = []; expired = 0
    const body = { code, message: '安全提示', data: { reasonCode: 'REASON_CODE' } }
    request.defaults.adapter = async config => ({ config, status: 200, data: body })
    await assert.rejects(request.get('/test', { silentFeedback }), caught => {
      assert.equal(caught.message, body.message)
      assert.equal(caught.response.status, 200)
      assert.deepEqual(caught.response.data, body)
      return true
    })
    assert.equal(expired, code === 401 ? 1 : 0)
    assert.equal(events.length, code === 401 || silentFeedback ? 0 : 1)
  }
})
