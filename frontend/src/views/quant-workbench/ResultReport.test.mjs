import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'

let server, component
before(async () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  // Capture the real report's chart option at the rendering boundary.
  const chartStub = {
    name: 'capture-chart-option', enforce: 'pre',
    resolveId(id) { if (id === 'vue-echarts') return '\0capture-chart-option' },
    load(id) { if (id === '\0capture-chart-option') return `import { h } from 'vue'; export default { props: ['option'], setup: props => () => h('pre', { class: 'chart-option' }, JSON.stringify(props.option)) }` },
  }
  server = await createServer({ root, configFile: false, plugins: [chartStub, vue()],
    ssr: { noExternal: ['vue-echarts'] },
    resolve: { alias: { '@': `${root}/src` } }, optimizeDeps: { noDiscovery: true, include: [] },
    server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' })
  component = (await server.ssrLoadModule('/src/views/quant-workbench/ResultReport.vue')).default
})
after(async () => { await server?.close() })

const render = result => renderToString(createSSRApp({ render: () => h(component, { result }) }))
const curve = equity => [{ date: '2023-01-03', equity, drawdown: .1 }]

test('report renders both signs and passes all three equity series to chart', async () => {
  for (const [netReturn, expected] of [[.28, '+8.00%'], [.05, '-15.00%']]) {
    const html = await render({ metrics: { netReturn, maxDrawdown: .1 }, equityCurve: curve(100),
      buyAndHold: { status: 'READY', metrics: { netReturn: .2, maxDrawdown: .2 }, equityCurve: curve(200) },
      benchmark: { status: 'READY', metrics: { netReturn: .15 }, equityCurve: curve(300) } })
    assert.ok(html.includes(expected))
    for (const text of ['策略累计收益', '买入并持有累计收益', '相对买入并持有', '策略最大回撤', '买入并持有最大回撤', '研究基准比较']) assert.ok(html.includes(text))
    const chart = html.match(/<pre[^>]*class="[^"]*chart-option[^"]*"[^>]*>(.*?)<\/pre>/s)
    assert.ok(chart, html)
    const serialized = chart[1].replaceAll('&quot;', '"')
    const option = JSON.parse(serialized)
    assert.deepEqual(option.series.slice(0, 3).map(s => [s.name, s.data[0][1]]), [['策略权益', 100], ['买入并持有', 200], ['研究基准权益', 300]])
  }
})

test('legacy results stay readable and unavailable baseline shows no comparison', async () => {
  assert.doesNotMatch(await render({ metrics: { netReturn: .1 } }), /买入并持有对比/)
  const html = await render({ metrics: { netReturn: .1 }, buyAndHold: { status: 'UNAVAILABLE', reason: 'BUY_AND_HOLD_ASSETS_NOT_FILLED' } })
  assert.match(html, /未能完成买入/)
  assert.doesNotMatch(html, /相对买入并持有/)
})
