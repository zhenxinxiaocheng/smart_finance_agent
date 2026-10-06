import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const stock = { id: 389, code: '600519', name: '贵州茅台', market: 'SSE', productType: 'STOCK' }
const fund = { id: 1, code: '010736', name: '易方达沪深300指数增强A', market: 'FUND_CN', productType: 'MUTUAL_FUND' }
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Vue.nextTick() }

function mountForm(overrides = {}, search = async () => ({ data: { items: [stock], total: 1 } }), resolve) {
  const requests = [], submissions = []
  const props = Vue.reactive({ productType: 'STOCK', submitting: false, existingCodes: [], ...overrides })
  const node = (type, text = '') => ({ type, text, props: {}, children: [], parent: null })
  const renderer = Vue.createRenderer({
    createElement: type => node(type), createText: text => node('text', text), createComment: text => node('comment', text),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] || null,
    patchProp: (el, key, previous, value) => { el.props[key] = value },
    insert: (el, parent, anchor = null) => {
      if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
      el.parent = parent
    },
    remove: el => { if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }
  })
  const Input = Vue.defineComponent({
    props: ['modelValue'], emits: ['update:modelValue'],
    setup: (inputProps, { attrs, emit }) => () => Vue.h('input', {
      ...attrs, value: inputProps.modelValue, onInput: event => emit('update:modelValue', event.target.value)
    })
  })
  const Button = (buttonProps, { slots }) => Vue.h('button', buttonProps, slots.default?.())
  const icon = () => Vue.h('span')
  const modules = {
    vue: Vue, '@lucide/vue': { LoaderCircle: icon, Search: icon },
    '@/components/ui/input': { Input }, '@/components/ui/button': { Button },
    '@/api/investment': {
      searchInvestmentAssetProductsAPI: async params => { requests.push(params); return search(params) },
      resolveInvestmentAssetAPI: resolve || (async () => { throw new Error('unexpected remote resolution') })
    }
  }
  const { descriptor } = parse(readFileSync(new URL('./InvestmentAssetAddForm.vue', import.meta.url), 'utf8'))
  const script = compileScript(descriptor, { id: 'asset-add-test', inlineTemplate: true }).content
    .replace(/import\s+\{([^}]+)\}\s+from\s+['"]([^'"]+)['"]/g, (_, bindings, module) =>
      `const { ${bindings.replace(/\s+as\s+/g, ': ')} } = modules[${JSON.stringify(module)}]`)
    .replace('export default', 'return')
  const component = new Function('modules', script)(modules)
  const root = node('root')
  const app = renderer.createApp({ render: () => Vue.h(component, { ...props, onSubmit: item => submissions.push(item) }) })
  app.mount(root)
  const all = el => [el, ...el.children.flatMap(all)]
  const text = el => el.type === 'comment' ? '' : el.text + el.children.map(text).join('')
  const input = () => all(root).find(el => el.type === 'input')
  const button = label => all(root).find(el => el.type === 'button' && text(el).trim() === label)
  return {
    props, requests, submissions, input, button, text: () => text(root), unmount: () => app.unmount(),
    fill: async value => { input().props.onInput({ target: { value } }); await Vue.nextTick() },
    click: async label => {
      const target = button(label)
      assert.ok(target, `应显示“${label}”按钮`)
      assert.ok(!target.props.disabled, `“${label}”按钮应可用`)
      target.props.onClick()
      await flush()
    }
  }
}

for (const keyword of ['贵州茅台', '600519']) {
  test(`股票可搜索${keyword}并从结果添加`, async t => {
    const form = mountForm()
    t.after(form.unmount)
    await form.fill(` ${keyword} `)
    await form.click('搜索')
    assert.deepEqual(form.requests, [{ keyword, productType: 'STOCK', page: 1 }])
    assert.match(form.text(), /贵州茅台/)
    await form.click('添加')
    assert.deepEqual(form.submissions, [{ productType: 'STOCK', code: '600519' }])
  })
}

test('基金支持长名称搜索，添加保留代码前导零', async t => {
  const form = mountForm({ productType: 'MUTUAL_FUND' }, async () => ({ data: { items: [fund], total: 1 } }))
  t.after(form.unmount)
  await form.fill('易方达沪深300指数增强')
  await form.click('搜索')
  assert.equal(form.input().props.maxlength, undefined)
  assert.equal(form.input().props.inputmode, undefined)
  assert.match(form.text(), /易方达沪深300指数增强A/)
  await form.click('添加')
  assert.deepEqual(form.submissions, [{ productType: 'MUTUAL_FUND', code: '010736' }])
})

test('空关键词禁止搜索，已有资产不能重复添加', async t => {
  const form = mountForm({ existingCodes: ['600519'] })
  t.after(form.unmount)
  assert.equal(form.button('搜索')?.props.disabled, true)
  await form.fill('600519')
  await form.click('搜索')
  assert.equal(form.button('已添加')?.props.disabled, true)
  assert.deepEqual(form.submissions, [])
})

test('改关键词后丢弃旧请求的结果', async t => {
  let finish
  const form = mountForm({}, () => new Promise(resolve => { finish = resolve }))
  t.after(form.unmount)
  await form.fill('贵州')
  await form.click('搜索')
  await form.fill('平安')
  finish({ data: { items: [stock], total: 1 } })
  await flush()
  assert.doesNotMatch(form.text(), /贵州茅台/)
  assert.equal(form.button('搜索').props.disabled, false)
})

test('切换类型后旧股票请求不会进入基金结果', async t => {
  let finish
  const form = mountForm({}, () => new Promise(resolve => { finish = resolve }))
  t.after(form.unmount)
  await form.fill('600519')
  await form.click('搜索')
  form.props.productType = 'MUTUAL_FUND'
  await Vue.nextTick()
  finish({ data: { items: [stock], total: 1 } })
  await flush()
  assert.doesNotMatch(form.text(), /贵州茅台/)
  assert.equal(form.input().props.value, '')
})

test('搜索失败不把内部异常暴露在弹窗中', async t => {
  const form = mountForm({}, async () => { throw new Error('java.lang.InternalError /private/provider') })
  t.after(form.unmount)
  await form.fill('贵州')
  await form.click('搜索')
  assert.match(form.text(), /搜索暂不可用/)
  assert.doesNotMatch(form.text(), /java\.lang|private|provider/)
})

test('加载更多保留首批结果并请求下一页', async t => {
  const second = { ...stock, id: 390, code: '000001', name: '平安银行' }
  const form = mountForm({}, async ({ page }) => ({ data: { items: page === 1 ? [stock] : [second], total: 2 } }))
  t.after(form.unmount)
  await form.fill('银行')
  await form.click('搜索')
  await form.click('加载更多')
  assert.deepEqual(form.requests.map(item => item.page), [1, 2])
  assert.match(form.text(), /贵州茅台/)
  assert.match(form.text(), /平安银行/)
  assert.equal(form.button('加载更多'), undefined)
})

test('目录尚未包含精确代码时仍可通过已有识别接口找到基金', async t => {
  const resolutions = []
  const form = mountForm({ productType: 'MUTUAL_FUND' }, async () => ({ data: { items: [], total: 0 } }),
    async item => { resolutions.push(item); return { data: fund } })
  t.after(form.unmount)
  await form.fill('010736')
  await form.click('搜索')
  assert.deepEqual(resolutions, [{ productType: 'MUTUAL_FUND', code: '010736' }])
  assert.match(form.text(), /易方达沪深300指数增强A/)
  await form.click('添加')
  assert.deepEqual(form.submissions, [{ productType: 'MUTUAL_FUND', code: '010736' }])
})
