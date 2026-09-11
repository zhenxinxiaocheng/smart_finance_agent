import assert from 'node:assert/strict'
import test from 'node:test'
import { explainBacktest, filterVersionBacktests } from './researchExplanation.js'

const scope = 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION'
const task = (overrides = {}) => ({ status: 'SUCCEEDED', qualification: { status: 'QUALIFIED', reasons: [], scope }, result: {}, ...overrides })

test('completion is distinct from qualification and existing risks survive a pass', () => {
  const report = explainBacktest(task({ result: { corporateActionWarnings: { a: ['2023-02-01'] } } }))
  assert.equal(report.status, 'QUALIFIED')
  assert.equal(report.warnings.length, 1)
  assert.match(report.warnings[0].details, /2023-02-01/)
  assert.match(report.scopeMeaning, /模拟运行/)
  for (const status of ['FAILED', 'CANCELLED', 'RUNNING', 'QUEUED']) {
    assert.equal(explainBacktest(task({ status })).status, null)
  }
  assert.equal(explainBacktest(task({ qualification: {} })).status, null)
})

test('unknown reasons are preserved and corporate action reasons merge with dates', () => {
  const report = explainBacktest(task({ qualification: { status: 'UNQUALIFIED', scope,
    reasons: ['CORPORATE_ACTION_UNSUPPORTED', 'NEW_REASON', 'NEW_REASON'] },
    result: { corporateActionWarnings: { a: ['2023-02-01'] } } }))
  assert.equal(report.reasons.length, 2)
  assert.equal(report.warnings.filter(w => w.code === 'CORPORATE_ACTION_UNSUPPORTED').length, 1)
  assert.ok(report.reasons.some(r => r.message.includes('NEW_REASON')))
  assert.equal(report.status, 'UNQUALIFIED')
})

test('effective zero costs are preserved and fund slippage is not presented as executed', () => {
  const report = explainBacktest(task({ result: { provenance: { config: {
    assetClass: 'FUND', feeRate: 0, sellFeeRate: 0, slippageBps: 5,
    initialCash: 250000, maxWeight: .3, maxDrawdown: .25, publicationLagDays: 1, settlementDays: 2, fundShareDecimals: 4,
  } } } }))
  assert.equal(report.assumptionSummary.find(r => r.key === 'feeRate').value, '0%')
  assert.equal(report.assumptionSummary.some(r => r.key === 'slippageBps'), false)
  assert.match(report.assumptionParameters.find(r => r.key === 'settlementDays').value, /日历日/)
  assert.match(report.assumptionSummary.find(r => r.key === 'maxDrawdown').label, /触发/)
})

test('request settings never become effective historical assumptions', () => {
  const report = explainBacktest(task({ researchContext: { requestConfig: { feeRate: .9, initialCash: 100 } } }))
  assert.deepEqual(report.assumptionSummary, [])
  assert.deepEqual(report.effectiveConfig, {})
  assert.equal(report.requestConfig.initialCash, 100)
  assert.deepEqual(report.warnings, [])
})

test('provenance wins without mutating the result and date meanings remain separate', () => {
  const input = task({ result: { provenance: { startDate: '2023-02-01', endDate: '2023-03-01', modelRef: 'engine-model' },
    equityCurve: [{ date: '2023-02-03' }, { date: '2023-02-28' }] },
    researchContext: { modelRef: 'request-model', requestedRange: { startDate: '2022-01-01' },
      dataSnapshot: [{ id: 'a', startDate: '2021-01-01', endDate: '2023-03-01', observations: 100 }] } })
  const before = JSON.stringify(input)
  const report = explainBacktest(input)
  assert.equal(report.modelRef, 'engine-model')
  assert.ok(report.warnings.some(w => w.code === 'SOURCE_REFERENCE_CONFLICT' && w.source.includes('modelRef')))
  assert.equal(report.evaluationRange.startDate, '2023-02-01')
  assert.equal(report.observedRange.startDate, '2023-02-03')
  assert.equal(report.dataSnapshot[0].startDate, '2021-01-01')
  assert.equal(JSON.stringify(input), before)
})

test('training task provenance conflicts are visible even if model refs agree', () => {
  const report = explainBacktest(task({ result: { provenance: { modelRef: 'same', modelTaskId: 'training-a' } },
    researchContext: { modelRef: 'same', modelTaskId: 'training-b' } }))
  assert.equal(report.modelTaskId, 'training-a')
  assert.ok(report.warnings.some(w => w.code === 'SOURCE_REFERENCE_CONFLICT' && w.source.includes('modelTaskId')))
})

test('legacy nested qualification is used only when top-level status is absent', () => {
  const report = explainBacktest(task({ qualification: {}, result: { qualification: { status: 'UNQUALIFIED', reasons: ['NO_EXECUTED_TRADES'] } } }))
  assert.equal(report.status, 'UNQUALIFIED')
  assert.match(report.scopeMeaning, /未记录/)
})

test('benchmark problems, errors and source conflicts stay visible without inventing missing data', () => {
  const report = explainBacktest(task({ status: 'FAILED', errorCode: 'INSUFFICIENT_DATA', errorMessage: 'no observations',
    result: { benchmark: { status: 'UNAVAILABLE', reason: 'provider missing' }, warnings: ['new warning'] },
    researchContext: { warnings: [{ code: 'SOURCE_REFERENCE_CONFLICT', source: 'universe', message: '引用不一致' }] } }))
  assert.equal(report.status, null)
  assert.ok(report.warnings.some(w => w.code === 'INSUFFICIENT_DATA'))
  assert.ok(report.warnings.some(w => w.details.includes('provider missing')))
  assert.ok(report.warnings.some(w => w.message.includes('new warning')))
  assert.ok(report.warnings.some(w => w.message.includes('引用不一致')))
  assert.equal(explainBacktest(task()).warnings.length, 0)
})

test('known limitations receive Chinese summaries, unknown assumptions retain their original text', () => {
  const report = explainBacktest(task({ result: { assumptions: [
    'Fixed snapshot universe; historical constituent membership and survivorship bias are not certified.',
    'NAV publication delay 1 calendar days; subscription share availability and redemption cash settlement 2 calendar days after confirmation.',
    'future engine assumption',
  ] } }))
  assert.match(report.assumptions[0].text, /幸存者偏差/)
  assert.match(report.assumptions[1].text, /1.*2/)
  assert.equal(report.assumptions[2].text, 'future engine assumption')
  assert.ok(report.warnings.some(w => w.source === 'assumptions'))
})

test('current revision filtering never substitutes the last frozen revision', () => {
  const versions = [{ id: 'v1', version: 1 }, { id: 'v2', version: 2 }]
  const tasks = [{ id: 'a', strategyVersionId: 'v1' }, { id: 'b', strategyVersionId: 'v2' }, { id: 'c', strategyVersionId: 'v2' }]
  assert.deepEqual(filterVersionBacktests(tasks, versions, 3, 'current'), [])
  assert.deepEqual(filterVersionBacktests(tasks, versions, 2, 'current').map(t => t.id), ['b', 'c'])
  assert.deepEqual(filterVersionBacktests(tasks, versions, 2, 'v1').map(t => t.id), ['a'])
  assert.equal(filterVersionBacktests(tasks, versions, 2, 'all').length, 3)
})
