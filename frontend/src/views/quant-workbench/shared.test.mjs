import assert from 'node:assert/strict'
import test, { before, after } from 'node:test'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
let server, useOperation
before(async () => {
  globalThis.window = { dispatchEvent() {} }
  const root = fileURLToPath(new URL('../../../', import.meta.url))
  server = await createServer({ root, configFile: false, resolve: { alias: { '@': `${root}/src` } },
    optimizeDeps: { noDiscovery: true }, server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  ;({useOperation} = await server.ssrLoadModule('/src/views/quant-workbench/shared.js'))
})
after(async () => { await server?.close(); delete globalThis.window })
test('operations expose a persistent safe error and retain useful blocking reasons', async () => {
  const operation = useOperation()
  await operation.run(async()=>{ throw new Error('java.lang.IllegalStateException: 配置错误') })
  assert.equal(operation.error.value, '操作未完成，请稍后重试。')
  assert.equal(operation.busy.value, false)
  await operation.run(async()=>{ throw {response:{data:{message:'策略已归档，不能启动新任务'}}} })
  assert.equal(operation.error.value, '策略已归档，不能启动新任务')
})
