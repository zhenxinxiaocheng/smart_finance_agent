import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

let createSessionExpiryHandler
try {
  ({ createSessionExpiryHandler } = await import('./sessionExpiry.js'))
} catch {
  // The first TDD run intentionally happens before the implementation exists.
}

test('并发鉴权失败只清理一次会话并跳转一次登录页', async () => {
  assert.equal(typeof createSessionExpiryHandler, 'function', '应提供统一的会话过期处理器')

  let clearCount = 0
  let redirectCount = 0
  const handler = createSessionExpiryHandler({
    clearSession: () => {
      clearCount += 1
    },
    redirectToLogin: async () => {
      redirectCount += 1
    }
  })

  await Promise.all([handler.handle(), handler.handle(), handler.handle()])

  assert.equal(clearCount, 1)
  assert.equal(redirectCount, 1)
})

test('重新登录后允许处理下一次会话过期', async () => {
  assert.equal(typeof createSessionExpiryHandler, 'function', '应提供统一的会话过期处理器')

  let redirectCount = 0
  const handler = createSessionExpiryHandler({
    clearSession: () => {},
    redirectToLogin: async () => {
      redirectCount += 1
    }
  })

  await handler.handle()
  handler.reset()
  await handler.handle()

  assert.equal(redirectCount, 2)
})

test('Axios 与聊天流请求共用会话过期处理器', () => {
  const requestSource = readFileSync(new URL('../api/request.js', import.meta.url), 'utf8')
  const chatSource = readFileSync(new URL('../api/chat.js', import.meta.url), 'utf8')

  assert.match(requestSource, /handleSessionExpired/)
  assert.match(chatSource, /handleSessionExpired/)
  assert.doesNotMatch(requestSource, /localStorage\.removeItem\(['"]token['"]\)/)
  assert.doesNotMatch(chatSource, /localStorage\.removeItem\(['"]token['"]\)/)
})
