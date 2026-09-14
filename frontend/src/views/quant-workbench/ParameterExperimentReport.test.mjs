import assert from 'node:assert/strict'
import test, { after, before } from 'node:test'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { createMemoryHistory, createRouter } from 'vue-router'
import { fileURLToPath } from 'node:url'

let server, component
before(async () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  server = await createServer({ root, configFile: false, plugins: [vue()],
    resolve: { alias: { '@': `${root}/src` } }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  component = (await server.ssrLoadModule('/src/views/quant-workbench/ParameterExperimentReport.vue')).default
})
after(async () => { await server?.close() })

async function render(detail) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
  await router.push('/')
  const app = createSSRApp({ render: () => h(component, { detail }) })
  app.use(router)
  return renderToString(app)
}

test('report presents stable negative conclusion and keeps technical evidence collapsed', async () => {
  const html = await render({ id: 'exp-1', status: 'SUCCEEDED', parameter: { key: 'maxWeight', baseline: 0.3 },
    summary: { classification: 'STABLE', performanceProfile: 'NEGATIVE', stableRange: [0.27, 0.33], direction: 'MIXED', directionConsistency: 0.75 },
    evidence: { quality: 'HIGH', controlIntegrity: 'PASS', dataConsistency: 'PASS', sourceCompleteness: 'COMPLETE', runCoverage: 'COMPLETE', validRuns: 5, totalRuns: 5 },
    provenance: { stabilityAlgorithmVersion: 'parameter-stability-v2', snapshot: { contentHash: 'secret-hash', metadata: { assetCount: 2 } } },
    runs: [{ id: 'run-1', ordinal: 2, value: 0.3, baseline: true, status: 'SUCCEEDED', metrics: { netReturn: -0.03, maxDrawdown: 0.1 }, qualification: { status: 'QUALIFIED', scope: 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION' } }] })
  assert.match(html, /参数行为较稳定，但本次纳入计算的候选结果整体为负收益/)
  assert.match(html, /五点参数结果/)
  assert.match(html, /当前值/)
  assert.match(html, /验证通过/)
  assert.match(html, /数据与运行环境/)
  assert.match(html, /assetCount/)
  assert.match(html, /secret-hash/)
  assert.doesNotMatch(html, /该区间整体收益为负/)
})

test('running report accepts a null summary without inventing progress or conclusions', async () => {
  const html = await render({ id: 'exp-2', status: 'RUNNING', progress: null, parameter: { key: 'lookback', baseline: 20 }, summary: null,
    evidence: null, provenance: {}, runs: [{ id: 'run-2', ordinal: 0, value: 16, baseline: false, status: 'RUNNING', progress: null }] })
  assert.match(html, /研究结论将在候选运行完成后生成/)
  assert.match(html, /运行中/)
  assert.doesNotMatch(html, /0%/)
})
