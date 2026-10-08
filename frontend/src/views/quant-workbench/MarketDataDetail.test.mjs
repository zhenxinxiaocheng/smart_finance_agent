import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import { parse, compileScript } from '@vue/compiler-sfc'
import { readFile } from 'node:fs/promises'
import { createRenderer, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { fileURLToPath } from 'node:url'

let server, component
before(async () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  server = await createServer({ root, configFile: false, plugins: [{
    name: 'market-detail-boundaries', enforce: 'pre',
    resolveId(id) {
      if (/\/api\/quantWorkbench(?:\.js)?$/.test(id)) return '\0market-api'
      if (/\/(button|table)$/.test(id)) return '\0market-ui'
      if (/\/lib\/feedback(?:\.js)?$/.test(id)) return '\0feedback'
    },
    async load(id) {
      if (/\/(MarketDataDetail|QuantPageHeader)\.vue$/.test(id)) {
        const { descriptor } = parse(await readFile(id, 'utf8'))
        return compileScript(descriptor, { id, inlineTemplate: true }).content
      }
      if (id === '\0market-api') return 'export const quant = { marketData: new Proxy({}, {get: (_, key) => (...args) => globalThis.__marketApi[key](...args)}) }'
      if (id === '\0feedback') return 'export const feedback = { error() {} }'
      if (id === '\0market-ui') return `import { h } from 'vue'; const cell = { render() { return h('div', this.$attrs, this.$slots.default?.()) } }; const button = { render() { return h('button', this.$attrs, this.$slots.default?.()) } }; export { button as Button, cell as Table, cell as TableBody, cell as TableCell, cell as TableHead, cell as TableHeader, cell as TableRow }`
    },
  }], resolve: { alias: { '@': `${root}/src` } }, optimizeDeps: { noDiscovery: true },
  server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  component = (await server.ssrLoadModule('/src/views/quant-workbench/MarketDataDetail.vue')).default
})
after(async () => { await server?.close(); delete globalThis.__marketApi })

const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const renderer = createRenderer({
  createElement: type => node(type), createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  patchProp: (el, key, previous, value) => { el.props[key] = value },
  insert: (el, parent, anchor) => {
    if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
    el.parent = parent
    const index = parent.children.indexOf(anchor)
    parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
  },
  remove: el => { const index = el.parent?.children.indexOf(el); if (index >= 0) el.parent.children.splice(index, 1) },
  parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
})
const contents = el => (el.type === 'comment' ? '' : el.text) + el.children.map(contents).join('')
const flush = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const detail = id => ({ id, name: id === '34493' ? '英伟达' : '苹果', code: id === '34493' ? 'NVDA' : 'AAPL',
  productType: 'STOCK', market: 'NASDAQ', currency: 'USD', status: 'ACTIVE', coverage: {
    historyStartDate: '2026-10-01', historyEndDate: '2026-10-07', observations: 5, adjustType: 'NONE', source: 'AKSHARE_US_SINA',
  } })

async function mount(t, api = {}) {
  const timers = new Map(), listeners = new Map(), calls = []; let timerId = 0
  t.mock.method(globalThis, 'setTimeout', (fn, delay) => { assert.equal(delay, 15000); timers.set(++timerId, fn); return timerId })
  t.mock.method(globalThis, 'clearTimeout', id => timers.delete(id))
  const originalDocument = globalThis.document
  globalThis.document = { hidden: false, addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) }
  const defaults = { detail: async id => detail(id), daily: async () => [{ tradeDate: '2026-10-07', closePrice: 187.53, volume: 100, source: 'AKSHARE_US_SINA' }], prepare: async () => ({ renewAfterMs: 60000 }) }
  globalThis.__marketApi = Object.fromEntries(Object.keys(defaults).map(key => [key, (...args) => { calls.push([key, ...args]); return (api[key] || defaults[key])(...args) }]))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/quant/data/:productId', component }, { path: '/quant/data', component: { render: () => null } }] })
  await router.push('/quant/data/34493')
  const root = node('root'), app = renderer.createApp({ render: () => h(RouterView) })
  app.use(router); app.mount(root)
  t.after(() => { app.unmount(); if (originalDocument === undefined) delete globalThis.document; else globalThis.document = originalDocument })
  return { root, app, router, timers, listeners, calls, async tick() {
    assert.equal(timers.size, 1)
    const [id, callback] = timers.entries().next().value
    timers.delete(id); await callback(); await flush()
  } }
}

test('stored quotes render when demand preparation fails, and retry clears the error', async t => {
  let attempts = 0
  const view = await mount(t, { prepare: async () => { if (++attempts === 1) throw new Error('数据准备暂不可用'); return { renewAfterMs: 60000 } } })
  await flush()
  assert.match(contents(view.root), /英伟达 · NVDA/)
  assert.match(contents(view.root), /187\.53/)
  assert.match(contents(view.root), /数据准备暂不可用/)
  await view.tick()
  assert.equal(attempts, 2)
  assert.match(contents(view.root), /187\.53/)
  assert.doesNotMatch(contents(view.root), /数据准备暂不可用/)
})

test('pending preparation does not block stored reads, polling or another product', async t => {
  const request = deferred()
  const view = await mount(t, { prepare: id => id === '34493' ? request.promise : Promise.resolve({ renewAfterMs: 60000 }) })
  await flush()
  assert.match(contents(view.root), /英伟达 · NVDA/)
  await view.tick()
  assert.equal(view.calls.filter(([key]) => key === 'prepare').length, 1)
  assert.equal(view.calls.filter(([key]) => key === 'daily').length, 2)
  await view.router.push('/quant/data/31548'); await flush()
  assert.match(contents(view.root), /苹果 · AAPL/)
  assert.doesNotMatch(contents(view.root), /英伟达 · NVDA/)
  assert.deepEqual(view.calls.filter(([key]) => key === 'prepare').map(([, id]) => id), ['34493', '31548'])
  request.reject(new Error('旧股票补数失败')); await flush()
  assert.doesNotMatch(contents(view.root), /旧股票补数失败/)
  await view.tick()
  assert.deepEqual(view.calls.filter(([key]) => key === 'prepare').map(([, id]) => id), ['34493', '31548'])
  view.app.unmount()
  assert.equal(view.timers.size, 0)
  assert.equal(view.listeners.size, 0)
})

test('late reads from the previous route cannot replace the selected product', async t => {
  const request = deferred()
  const view = await mount(t, { detail: id => id === '34493' ? request.promise : Promise.resolve(detail(id)) })
  await flush()
  await view.router.push('/quant/data/31548'); await flush()
  request.resolve(detail('34493')); await flush()
  assert.match(contents(view.root), /苹果 · AAPL/)
  assert.doesNotMatch(contents(view.root), /英伟达 · NVDA/)
  assert.equal(view.timers.size, 1)
})

test('the selected product reports its own preparation failure despite an older pending request', async t => {
  const previous = deferred(); let selectedAttempts = 0
  const view = await mount(t, { prepare: id => {
    if (id === '34493') return previous.promise
    if (++selectedAttempts === 1) return Promise.reject(new Error('苹果数据准备暂不可用'))
    return Promise.resolve({ renewAfterMs: 60000 })
  } })
  await flush(); await view.router.push('/quant/data/31548'); await flush()
  assert.match(contents(view.root), /苹果 · AAPL/)
  assert.match(contents(view.root), /苹果数据准备暂不可用/)
  previous.resolve({ renewAfterMs: 60000 }); await flush()
  assert.match(contents(view.root), /苹果数据准备暂不可用/)
  await view.tick()
  assert.equal(selectedAttempts, 2)
  assert.doesNotMatch(contents(view.root), /苹果数据准备暂不可用/)
})

test('read failure is shown and recovers through polling while preparation succeeds', async t => {
  let reads = 0
  const view = await mount(t, { daily: async () => { if (++reads === 1) throw new Error('行情读取暂不可用'); return [{ tradeDate: '2026-10-07', closePrice: 187.53 }] } })
  await flush()
  assert.match(contents(view.root), /行情读取暂不可用/)
  await view.tick()
  assert.match(contents(view.root), /187\.53/)
  assert.doesNotMatch(contents(view.root), /行情读取暂不可用/)
})

test('read failure from the previous route is ignored while the new route is still loading', async t => {
  const previous = deferred(), selected = deferred()
  const view = await mount(t, { detail: id => id === '34493' ? previous.promise : selected.promise })
  await flush(); await view.router.push('/quant/data/31548'); await flush()
  previous.reject(new Error('旧股票行情读取失败')); await flush()
  assert.doesNotMatch(contents(view.root), /旧股票行情读取失败/)
  selected.resolve(detail('31548')); await flush()
  assert.match(contents(view.root), /苹果 · AAPL/)
})

test('hidden pages stop reads and resume one polling loop on visibility change', async t => {
  const view = await mount(t)
  await flush()
  globalThis.document.hidden = true
  await view.tick()
  assert.equal(view.calls.filter(([key]) => key === 'daily').length, 1)
  assert.equal(view.timers.size, 0)
  globalThis.document.hidden = false
  view.listeners.get('visibilitychange')(); await flush()
  assert.equal(view.calls.filter(([key]) => key === 'daily').length, 2)
  assert.equal(view.timers.size, 1)
})

test('unmount during preparation stops polling and ignores its late failure', async t => {
  const request = deferred(), view = await mount(t, { prepare: () => request.promise })
  await flush(); view.app.unmount()
  request.reject(new Error('离开页面后的失败')); await flush()
  assert.equal(view.timers.size, 0)
  assert.equal(view.listeners.size, 0)
})
