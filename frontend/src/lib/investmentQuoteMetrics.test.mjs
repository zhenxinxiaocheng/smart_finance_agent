import test from 'node:test'
import assert from 'node:assert/strict'

import { compactNumber, signedPercent } from './investmentQuoteMetrics.js'

test('signedPercent formats positive negative and missing quote changes', () => {
  assert.equal(signedPercent('1.2588'), '+1.26%')
  assert.equal(signedPercent('-3.15'), '-3.15%')
  assert.equal(signedPercent(null), '-')
})

test('compactNumber uses Chinese market units', () => {
  assert.equal(compactNumber('21755100'), '2175.51万')
  assert.equal(compactNumber('1200000000'), '12亿')
  assert.equal(compactNumber(null), '-')
})
