import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applicableSensitivityParameters,
  explainExperiment,
  explainExperimentError,
  frozenStrategyType,
  createEligibilityLoader,
  explainRunValidation,
} from './experimentExplanation.js'
import { qualificationNotice } from './researchExplanation.js'

const baseDetail = overrides => ({
  id: 'exp-1', name: '最大权重参数敏感性', status: 'SUCCEEDED',
  parameter: { key: 'maxWeight', baseline: 0.3, candidates: [0.24, 0.27, 0.3, 0.33, 0.36] },
  strategy: { id: 'strategy-1', versionId: 'version-1', version: 4, name: '多因子策略' },
  researchWindow: { startDate: '2024-01-01', endDate: '2025-01-01' },
  summary: {
    classification: 'STABLE', performanceProfile: 'NEGATIVE', stableRange: [0.27, 0.33],
    direction: 'MIXED', directionConsistency: 0.75, returnTolerance: 0.02,
    drawdownTolerance: 0.01, localSensitivity: { returnSlope: -0.1 }, isolatedPeak: false,
    reasons: ['LOCAL_RESULTS_CONSISTENT'], qualificationSummary: { qualified: 4, unqualified: 1, missing: 0 },
  },
  runs: [], evidence: { quality: 'HIGH', validRuns: 5, totalRuns: 5 },
  provenance: { stabilityAlgorithmVersion: 'parameter-stability-v2', evidenceSchemaVersion: 'experiment-evidence-v2' },
  ...overrides,
})

test('stable negative wording describes all included candidates, not the stable range', () => {
  const report = explainExperiment(baseDetail())
  assert.equal(report.performanceProfileText, '整体负收益')
  assert.equal(report.conclusionNotice, '参数行为较稳定，但本次纳入计算的候选结果整体为负收益。')
  assert.doesNotMatch(report.conclusionNotice, /该区间/)
})

test('v1 direction consistency remains historical text while v2 is a local percentage', () => {
  const legacy = explainExperiment(baseDetail({
    provenance: { stabilityAlgorithmVersion: 'parameter-stability-v1' },
    summary: { ...baseDetail().summary, directionConsistency: 'CONSISTENT' },
  }))
  assert.equal(legacy.directionConsistencyText, 'CONSISTENT')
  assert.match(legacy.algorithmNotice, /历史含义/)

  const current = explainExperiment(baseDetail())
  assert.equal(current.directionConsistencyText, '75.00%')
  assert.match(current.algorithmNotice, /相邻候选点的局部表现/)
  assert.match(current.algorithmNotice, /不是未来收益趋势预测/)
})

test('unknown stability versions are not reinterpreted as v2', () => {
  const report = explainExperiment(baseDetail({
    provenance: { stabilityAlgorithmVersion: 'parameter-stability-v9' },
  }))
  assert.equal(report.directionConsistencyText, '无法解释')
  assert.equal(report.directionText, '无法解释')
  assert.match(report.algorithmNotice, /旧版或未知判断算法/)
})

test('performance profiles and evidence quality remain independent', () => {
  for (const [profile, expected] of Object.entries({ POSITIVE: '整体正收益', NEGATIVE: '整体负收益', MIXED: '正负表现混合', FLAT: '表现接近平坦' })) {
    const report = explainExperiment(baseDetail({ summary: { ...baseDetail().summary, performanceProfile: profile }, evidence: { quality: 'LOW' } }))
    assert.equal(report.performanceProfileText, expected)
    assert.equal(report.evidenceQualityText, '低')
  }
})

test('catalog filtering uses frozen strategy type and only enabled sensitivity rules', () => {
  const catalog = { parameters: [
    { key: 'lookback', title: '观察窗口', sensitivity: { enabled: true, strategyTypes: ['TREND'] } },
    { key: 'maxWeight', sensitivity: { enabled: true, strategyTypes: ['TREND', 'MULTI_FACTOR'] } },
    { key: 'feeRate', sensitivity: { enabled: false, strategyTypes: ['TREND'] } },
  ] }
  assert.deepEqual(applicableSensitivityParameters(catalog, 'MULTI_FACTOR').map(item => item.key), ['maxWeight'])
  assert.deepEqual(applicableSensitivityParameters(catalog, null), [])
})

test('strategy type comes only from source backtest effective provenance config', () => {
  assert.equal(frozenStrategyType({ result: { provenance: { config: { strategyType: 'TREND' } } },
    researchContext: { requestConfig: { strategyType: 'MULTI_FACTOR' } } }), 'TREND')
  assert.equal(frozenStrategyType({ config: { strategyType: 'TREND' } }), null)
})

test('structured experiment errors map reasonCode before safe message', () => {
  const mapped = explainExperimentError({ response: { data: { message: '后端兜底', data: {
    errorCode: 'CURRENT_RUNTIME_CONFIG_INCOMPATIBLE', reasonCode: 'MODEL_CONFIG_MISMATCH',
  } } } })
  assert.equal(mapped.message, '当前模型配置与来源回测不一致，请重新进行正式回测')
  assert.equal(mapped.reasonCode, 'MODEL_CONFIG_MISMATCH')

  const fallback = explainExperimentError({ response: { data: { message: '安全提示', data: { reasonCode: 'NEW_CODE' } } } })
  assert.equal(fallback.message, '安全提示')
  assert.equal(fallback.reasonCode, 'NEW_CODE')
})

test('qualification scope keeps paper eligibility separate from profit claims', () => {
  const report = explainExperiment(baseDetail({ runs: [{ id: 'run-1', ordinal: 2, baseline: true, status: 'SUCCEEDED',
    qualification: { status: 'QUALIFIED', scope: 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION' } }] }))
  assert.equal(report.qualificationNotice, qualificationNotice)
  assert.match(report.qualificationNotice, /^通过验证代表满足/)
})

test('eligibility loader caches only successful responses and retries transient failures', async () => {
  let calls = 0
  const load = createEligibilityLoader(async id => {
    calls += 1
    if (calls === 1) throw new Error('temporary')
    return { eligible: true, sourceBacktestId: id }
  })
  await assert.rejects(load('backtest-1'), /temporary/)
  assert.deepEqual(await load('backtest-1'), { eligible: true, sourceBacktestId: 'backtest-1' })
  assert.deepEqual(await load('backtest-1'), { eligible: true, sourceBacktestId: 'backtest-1' })
  assert.equal(calls, 2)
})

test('run validity and scope are displayed only from recorded fields', () => {
  const scope = 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION'
  assert.deepEqual(explainRunValidation({ qualification: { scope }, validation: { valid: false, code: 'PASS' } }), {
    scopeText: '模拟运行准入检查', validationText: '未通过',
  })
  assert.equal(explainRunValidation({ qualification: { scope: 'NEW_SCOPE' } }).scopeText, '暂无法解释')
  assert.equal(explainRunValidation({ validation: { code: 'PASS' } }).validationText, '未记录')
  assert.equal(explainRunValidation({ validation: { valid: true } }).validationText, '通过')
  const detail = baseDetail({ runs: [{ qualification: { status: 'UNQUALIFIED' } }],
    provenance: { sourceRuntime: { codeHash: 'old' }, experimentRuntime: { codeHash: 'new' }, stabilityAlgorithmVersion: 'parameter-stability-v1' }, summary: { directionConsistency: 'INCREASING' } })
  const report = explainExperiment(detail)
  assert.equal(report.evidenceQualityText, '高')
  assert.equal(report.provenance.sourceRuntime.codeHash, 'old')
  assert.equal(report.provenance.experimentRuntime.codeHash, 'new')
  assert.equal(report.directionText, '未记录')
  assert.equal(report.directionConsistencyText, 'INCREASING')
})
