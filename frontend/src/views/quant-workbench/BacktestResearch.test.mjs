import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createRouter, createMemoryHistory } from 'vue-router'
import { explainBacktest } from './researchExplanation.js'

let server, component
before(async () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  server = await createServer({ root, configFile: false, plugins: [vue()],
    resolve: { alias: { '@': `${root}/src` } }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  component = (await server.ssrLoadModule('/src/views/quant-workbench/BacktestResearch.vue')).default
})
after(async () => { await server?.close() })

async function render(task, summary = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
  await router.push('/')
  const app = createSSRApp({ render: () => h(component, { report: explainBacktest(task), summary }) })
  app.use(router)
  return renderToString(app)
}

test('default conclusion keeps qualification distinct and does not expose parameter JSON', async () => {
  const html = await render({ status: 'SUCCEEDED', qualification: { status: 'QUALIFIED' },
    result: { provenance: { config: { feeRate: 0, seed: 42 } } } }, true)
  assert.doesNotMatch(html, /验证通过|不代表未来盈利已经得到证明|QUALIFIED/)
  assert.doesNotMatch(html, /seed|<pre/)
})

test('details hide empty warnings and keep technical data collapsed', async () => {
  const html = await render({ status: 'SUCCEEDED', result: { provenance: { config: { feeRate: 0 } } } })
  assert.match(html, /0%/)
  assert.doesNotMatch(html, /验证范围|qualification|scope/)
  assert.doesNotMatch(html, /id="backtest-warnings"/)
  assert.doesNotMatch(html, /<details[^>]*\sopen(?:\s|>|=)/)
})

test('failed task errors remain readable and deleted strategy snapshots have no current link', async () => {
  const html = await render({ status: 'FAILED', errorCode: 'INSUFFICIENT_DATA', errorMessage: 'asset-a: no observations',
    researchContext: { lineage: { strategy: { state: 'AVAILABLE', objectId: 's1', versionId: 'v1', version: 1,
      currentStatus: 'DELETED', snapshot: { name: '旧策略' } } } } })
  assert.doesNotMatch(html, /asset-a: no observations/)
  assert.match(html, /旧策略/)
  assert.doesNotMatch(html, /href="\/quant\/strategies/)
  assert.doesNotMatch(html, /class="error/)
})

test('live strategy link selects the exact frozen version', async () => {
  const html = await render({ status: 'SUCCEEDED', researchContext: { lineage: { strategy: {
    state: 'AVAILABLE', objectId: 's1', versionId: 'v1', version: 1, currentStatus: 'DRAFT', snapshot: {},
  } } } })
  assert.doesNotMatch(html, /<pre|原始|版本记录 ID/)
})
