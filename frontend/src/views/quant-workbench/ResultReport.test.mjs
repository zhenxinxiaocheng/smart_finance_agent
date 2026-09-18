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
      monthlyReturns: [{month:'2023-01', return:.28}], trackingIndex: {status:'READY', name:'纳斯达克100', metrics:{netReturn:.15,maxDrawdown:.1}, equityCurve:curve(300), monthlyReturns:[{month:'2023-01',return:.15}]}, benchmark: { status: 'READY', metrics: { netReturn: .15 }, equityCurve: curve(900) } })
    assert.ok(html.includes(expected))
    for (const text of ['策略累计收益', '买入并持有累计收益', '相对买入并持有', '策略最大回撤', '买入并持有最大回撤', '表现对比', '收益走势', '回撤', '月度收益']) assert.ok(html.includes(text))
    const chart = html.match(/<pre[^>]*class="[^"]*chart-option[^"]*"[^>]*>(.*?)<\/pre>/s)
    assert.ok(chart, html)
    assert.doesNotMatch(html, /研究基准比较|研究基准权益|研究基准收益|相对研究基准|匹配目标仓位/)
    const charts = [...html.matchAll(/<pre[^>]*class="[^"]*chart-option[^"]*"[^>]*>(.*?)<\/pre>/gs)].map(m => JSON.parse(m[1].replaceAll('&quot;', '"')))
    assert.equal(charts.length, 3)
    assert.equal(charts[1].series.length, 3)
    assert.equal(charts[2].series[0].type, 'bar')
    for (const chart of charts.slice(0, 2)) {
      assert.ok(chart.series.every(series => series.emphasis?.disabled === true))
    }
    assert.deepEqual(charts[2].xAxis.data, ['2023-01'])
    assert.deepEqual(charts[2].series[1].data, [null])
    const serialized = chart[1].replaceAll('&quot;', '"')
    const option = JSON.parse(serialized)
    assert.deepEqual(option.series.slice(0, 3).map(s => [s.name, s.data[0][1]]), [['策略', 100], ['买入并持有', 200], ['跟踪指数 · 纳斯达克100', 300]])
  }
})

test('legacy results stay readable and unavailable baseline shows no comparison', async () => {
  assert.doesNotMatch(await render({ metrics: { netReturn: .1 } }), /买入并持有对比/)
  const html = await render({ metrics: { netReturn: .1 }, buyAndHold: { status: 'UNAVAILABLE', reason: 'BUY_AND_HOLD_ASSETS_NOT_FILLED' } })
  assert.match(html, /未能完成买入/)
  assert.doesNotMatch(html, /相对买入并持有/)
})

test('without tracking all charts have two series and the strategy summary is unchanged', async () => {
  const result = {metrics:{netReturn:.1,annualizedReturn:.2,maxDrawdown:.03,fees:12,tradeCount:2},
    equityCurve:curve(110),monthlyReturns:[{month:'2023-01',return:.1}],
    buyAndHold:{status:'READY',metrics:{netReturn:.2,maxDrawdown:.04,fees:99,annualizedReturn:.5},equityCurve:curve(120),monthlyReturns:[{month:'2023-01',return:.2}]}}
  const html = await render(result)
  const charts = [...html.matchAll(/<pre[^>]*class="[^"]*chart-option[^"]*"[^>]*>(.*?)<\/pre>/gs)].map(m=>JSON.parse(m[1].replaceAll('&quot;','"')))
  assert.equal(charts.length,3)
  for (const chart of charts) assert.deepEqual(chart.series.map(s=>s.name),['策略','买入并持有'])
  const summary = html.slice(0,html.indexOf('表现对比'))
  for (const text of ['累计收益','年化收益','最大回撤','总费用','成交笔数','10.00%','20.00%','3.00%']) assert.ok(summary.includes(text))
  assert.doesNotMatch(html,/买入并持有费用|买入并持有年化收益|跟踪指数/)
})
