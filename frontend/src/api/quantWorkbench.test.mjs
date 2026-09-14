import assert from 'node:assert/strict'
import test, { after, before } from 'node:test'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

let server, request, quant
before(async () => {
  globalThis.window = { dispatchEvent() {} }
  globalThis.localStorage = { getItem: () => null }
  const root = fileURLToPath(new URL('../../', import.meta.url))
  server = await createServer({ root, configFile: false, resolve: { alias: { '@': `${root}/src` } },
    optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  request = (await server.ssrLoadModule('/src/api/request.js')).default
  quant = (await server.ssrLoadModule('/src/api/quantWorkbench.js')).quant
})
after(async () => { await server?.close(); delete globalThis.window; delete globalThis.localStorage })

test('experiment API sends eligibility query and explicit idempotency header', async () => {
  const calls = []
  const previous = request.defaults.adapter
  request.defaults.adapter = async config => {
    calls.push(config)
    return { data: { code: 200, message: 'success', data: { id: 'exp-1' } }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    await quant.experiments.eligibility('backtest/1')
    await quant.experiments.create({ sourceBacktestId: 'backtest/1', parameterKey: 'maxWeight', name: '测试' }, 'idem-1')
  } finally {
    request.defaults.adapter = previous
  }
  assert.equal(calls[0].url, '/quant/v2/experiments/eligibility')
  assert.deepEqual(calls[0].params, { sourceBacktestId: 'backtest/1' })
  assert.equal(calls[1].url, '/quant/v2/experiments')
  assert.equal(calls[1].headers.get('Idempotency-Key'), 'idem-1')
})
