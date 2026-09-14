import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import { parse, compileScript } from '@vue/compiler-sfc'
import { readFile } from 'node:fs/promises'
import { createRenderer, h, reactive, nextTick } from 'vue'
import { routeLocationKey } from 'vue-router'
import { fileURLToPath } from 'node:url'

let server, component
before(async () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  server = await createServer({ root, configFile: false, plugins: [
    { name: 'detail-boundaries', enforce: 'pre',
      resolveId(id) {
        if (id.endsWith('/api/quantWorkbench.js')) return '\0quant'
        if (/\/(button|badge)$/.test(id)) return '\0ui'
        if (['./QuantPageHeader.vue', './QuantStatusBadge.vue', './ParameterExperimentReport.vue'].includes(id)) return '\0child'
      },
      async load(id) {
        if (id.endsWith('/ParameterExperimentDetail.vue')) {
          const { descriptor } = parse(await readFile(id, 'utf8'))
          return compileScript(descriptor, { id: 'detail-lifecycle-test', inlineTemplate: true }).content
        }
        if (id === '\0quant') return 'export const quant = { experiments: { get: (...args) => globalThis.__detailFetch(...args) } }'
        if (id === '\0child') return 'export default { render() { return null } }'
        if (id === '\0ui') return 'const component = { render() { return this.$slots.default?.() } }; export {component as Button, component as Badge}'
      },
    }], resolve: { alias: { '@': `${root}/src` } },
    optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  component = (await server.ssrLoadModule('/src/views/quant-workbench/ParameterExperimentDetail.vue')).default
})
after(async () => { await server?.close(); delete globalThis.__detailRoute; delete globalThis.__detailFetch })

// A small Vue host exercises real component lifecycle and usePoll without a DOM framework.
const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const renderer = createRenderer({
  createElement: type => node(type), createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
  patchProp: (el, key, previous, value) => { el.props[key] = value },
  insert: (el, parent, anchor) => { el.parent = parent; const index = parent.children.indexOf(anchor); parent.children.splice(index < 0 ? parent.children.length : index, 0, el) },
  remove: el => { const index = el.parent?.children.indexOf(el); if (index >= 0) el.parent.children.splice(index, 1) },
  parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1],
})
const contents = el => (el.type === 'comment' ? '' : el.text) + el.children.map(contents).join('')
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }

test('detail uses four-second silent polling, preserves progress and clears timer on unmount', async t => {
  const timers = new Map(); let timerId = 0, calls = 0
  t.mock.method(globalThis, 'setTimeout', (fn, delay) => { assert.equal(delay, 4000); timers.set(++timerId, fn); return timerId })
  t.mock.method(globalThis, 'clearTimeout', id => timers.delete(id))
  globalThis.__detailRoute = reactive({ params: { id: 'a' } })
  globalThis.__detailFetch = async (id, options) => {
    calls++
    assert.equal(options.silent, calls > 1)
    if (calls === 2) throw { response: { status: 503 } }
    return { id, status: calls >= 4 ? 'SUCCEEDED' : 'RUNNING', progress: null }
  }
  const root = node('root'), app = renderer.createApp({ render: () => h(component) })
  app.provide(routeLocationKey, globalThis.__detailRoute)
  app.component('RouterLink', { render() { return this.$slots.default?.() } })
  app.mount(root)
  const tick = async () => { const [id, callback] = timers.entries().next().value; timers.delete(id); await callback(); await flush() }
  try {
    await flush()
    assert.match(contents(root), /当前阶段未报告百分比进度/)
    assert.doesNotMatch(contents(root), /0%/)
    await tick()
    assert.match(contents(root), /自动刷新暂时失败，正在继续尝试/)
    assert.match(contents(root), /当前阶段未报告百分比进度/)
    await tick()
    assert.doesNotMatch(contents(root), /自动刷新暂时失败/)
    await tick(); await tick()
    assert.equal(calls, 4)
  } finally { app.unmount() }
  assert.equal(timers.size, 0)
})
