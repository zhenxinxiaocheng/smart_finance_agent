import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('feedback renders above sheets and dialogs', () => {
  const source = readFileSync(new URL('./AppFeedback.vue', import.meta.url), 'utf8')
  assert.match(source, /z-\[100\]/)
})
