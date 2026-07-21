import assert from 'node:assert/strict'
import test from 'node:test'

import { createInvestmentAssetDraftController } from './investmentAssetDraft.js'

test('realtime asset refresh does not overwrite an open form draft', () => {
  const form = { quantity: '', averageCost: '', note: '' }
  const controller = createInvestmentAssetDraftController(form)

  controller.sync(true, { id: 7, quantity: 100, averageCost: 12.5, note: '原备注' })
  form.quantity = '200'
  form.averageCost = '13.5'

  controller.sync(true, { id: 7, quantity: 100, averageCost: 12.5, note: '原备注' })

  assert.equal(form.quantity, '200')
  assert.equal(form.averageCost, '13.5')
})

test('opening the drawer or switching assets initializes the draft', () => {
  const form = { quantity: '', averageCost: '', note: '' }
  const controller = createInvestmentAssetDraftController(form)

  controller.sync(false, { id: 7, quantity: 100, averageCost: 12.5, note: '旧' })
  controller.sync(true, { id: 7, quantity: 100, averageCost: 12.5, note: '旧' })
  assert.deepEqual(form, { quantity: '100', averageCost: '12.5', note: '旧' })

  controller.sync(true, { id: 8, quantity: 300, averageCost: 9.8, note: null })
  assert.deepEqual(form, { quantity: '300', averageCost: '9.8', note: '' })
})
