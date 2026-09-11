// Presentation only: the engine remains the sole source of qualification decisions.
export const researchLabels = {
  CORPORATE_ACTION_UNSUPPORTED: '检测到疑似分红、除权或价格异常，尚未完成还原核验',
  CORPORATE_ACTIONS_NOT_VERIFIED: '缺少分红除权核验，不满足模拟运行准入条件',
  INSUFFICIENT_EVALUATION_DATES: '有效评估日期不足',
  NO_EXECUTED_TRADES: '没有实际模拟成交',
  DRAWDOWN_LIMIT_EXCEEDED: '最大回撤达到或超过设定上限',
  MODEL_FINAL_HOLDOUT_UNQUALIFIED: '模型最终留出集未通过验证',
  FINAL_HOLDOUT_IC_NOT_POSITIVE: '最终留出集预测相关性未大于零',
  FINAL_HOLDOUT_NOT_BETTER_THAN_TRAIN_MEAN: '最终留出集误差未优于训练均值基线',
  INSUFFICIENT_DATA: '可用历史数据不足',
  FACTOR_DATA_UNAVAILABLE: '所选因子缺少必要数据',
  RAW_PRICE_REQUIRED: '缺少明确的未复权成交价格',
  MODEL_LOOKAHEAD: '回测区间与模型最终评估区间重叠',
}
export const qualificationNotice = '通过验证代表满足当前模拟运行准入条件，不代表未来盈利已经得到证明。'
const record = value => value && typeof value === 'object' && !Array.isArray(value) ? value : {}
const list = value => Array.isArray(value) ? value : value == null ? [] : [value]
export const researchValue = value => value == null || value === '' ? '未记录' : typeof value === 'object' ? JSON.stringify(value) : String(value)
const known = value => value !== null && value !== undefined && value !== ''
const text = value => typeof value === 'string' ? value : JSON.stringify(value) ?? ''

function reason(value) {
  const raw = record(value)
  const code = String(raw.code || raw.reason || (typeof value === 'string' ? value : 'WARNING'))
  return { code, message: researchLabels[code] || raw.message || text(value), raw: value }
}

function explainAssumption(raw) {
  const original = text(raw)
  let explanation = original
  let risk = false
  if (original === 'Fixed snapshot universe; historical constituent membership and survivorship bias are not certified.') {
    explanation = '使用固定资产池快照，未验证历史成分变更和幸存者偏差。'; risk = true
  } else if (original === 'Signal at observed close, execution on a later session; no intraday execution.') {
    explanation = '根据已观察到的收盘数据生成信号，在后续会话成交，不模拟日内交易。'
  } else if (original === 'Fund dividends/reinvestment are unsupported; discontinuities block certification.') {
    explanation = '暂不支持基金分红和再投资；检测到相关价格异常时阻止通过验证。'; risk = true
  } else if (original === '100-share buy lots; configured proportional fees/slippage; price limits enforced only when provider supplies limits.') {
    explanation = '股票和 ETF 按 100 份整数单位模拟交易，使用配置的比例费用及滑点；仅在数据提供涨跌停价格时检查价格限制。'; risk = true
  } else {
    let match = original.match(/^Trade consideration and fees rounded to CNY cents using HALF_UP; fund share precision (\d+) decimal places\.$/)
    if (match) explanation = `成交金额和费用按人民币分四舍五入；基金份额保留 ${match[1]} 位小数。`
    match = original.match(/^NAV publication delay (\d+) calendar days; subscription share availability and redemption cash settlement (\d+) calendar days after confirmation\.$/)
    if (match) explanation = `净值公布延迟按 ${match[1]} 个日历日；申购份额可用及赎回款到账按确认后 ${match[2]} 个日历日模拟，并非已核实的产品条款。`
    match = original.match(/^Fund fees are configurable assumptions: subscription ([\d.e+-]+), redemption ([\d.e+-]+)\.$/)
    if (match) explanation = `基金申购费率按 ${percent(Number(match[1]))}、赎回费率按 ${percent(Number(match[2]))} 模拟，需核对实际产品费率。`
  }
  return { text: explanation, original, risk }
}

const percent = value => `${Number((value * 100).toFixed(8))}%`
const number = value => Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8 })
const parameters = [
  ['feeRate', '买入费率', percent], ['sellFeeRate', '卖出费率', percent],
  ['slippageBps', '成交滑点', v => `${number(v)} 基点`],
  ['initialCash', '初始资金', v => `${number(v)} 元`],
  ['maxWeight', '单标的目标权重上限', percent], ['maxDrawdown', '回撤清仓触发阈值', percent],
  ['publicationLagDays', '净值公布假设延迟', v => `${number(v)} 个日历日`],
  ['settlementDays', '确认后份额可用 / 赎回款到账', v => `${number(v)} 个日历日`],
  ['fundShareDecimals', '基金份额精度', v => `${number(v)} 位小数`],
]

export function explainBacktest(task = {}) {
  const result = record(task.result)
  const context = record(task.researchContext)
  const provenance = record(result.provenance)
  const top = typeof task.qualification === 'string' ? { status: task.qualification } : record(task.qualification)
  const qualification = top.status ? top : record(result.qualification)
  const status = task.status === 'SUCCEEDED' && known(qualification.status) ? qualification.status : null
  const reasons = [...new Map(list(qualification.reasons).map(r => { const item = reason(r); return [item.code, item] })).values()]
  const scope = qualification.scope
  const scopeMeaning = scope === 'PAPER_ELIGIBILITY_ONLY_NOT_PROFITABILITY_CERTIFICATION'
    ? '本次仅验证当前模拟运行准入条件，未证明未来盈利，也不要求回测盈利或跑赢基准。'
    : known(scope) ? `引擎返回的验证范围：${researchValue(scope)}` : '本次未记录验证范围，不能据此推定已完成哪些检查。'
  const effectiveConfig = record(provenance.config)
  const fund = effectiveConfig.assetClass === 'FUND'
  const assumptionParameters = parameters.filter(([key]) => known(effectiveConfig[key])
    && (['publicationLagDays', 'settlementDays', 'fundShareDecimals'].includes(key) ? fund : key !== 'slippageBps' || !fund))
    .map(([key, label, format]) => ({ key, label, value: typeof effectiveConfig[key] === 'number' && Number.isFinite(effectiveConfig[key]) ? format(effectiveConfig[key]) : researchValue(effectiveConfig[key]) }))
  const assumptions = list(result.assumptions).map(explainAssumption)
  const warnings = new Map()
  const add = (source, code, message, details = '') => {
    const key = `${source}:${code}`
    const previous = warnings.get(key)
    const combined = [...new Set([previous?.details, details].filter(Boolean))].join('\n')
    warnings.set(key, { key, source, code, message: previous?.message || message, details: combined })
  }
  for (const r of reasons) add('qualification', r.code, r.message, text(r.raw))
  const corporate = record(result.corporateActionWarnings)
  if (Object.keys(corporate).length) {
    add('qualification', 'CORPORATE_ACTION_UNSUPPORTED', researchLabels.CORPORATE_ACTION_UNSUPPORTED,
      Object.entries(corporate).map(([id, dates]) => {
        const asset = list(context.dataSnapshot).find(a => String(a.id) === id)
        return `${asset?.name || id}（${id}）：${list(dates).map(text).join('、')}`
      }).join('\n'))
  }
  if (task.errorCode || task.errorMessage) add('task', task.errorCode || 'TASK_ERROR', researchLabels[task.errorCode] || '任务执行未完成', task.errorMessage || '')
  const benchmark = record(result.benchmark)
  if (known(benchmark.status) && benchmark.status !== 'READY')
    add('benchmark', String(benchmark.status), '本次基准不可用于完整比较', text(benchmark.reason || benchmark.status))
  if (benchmark.status === 'READY' && benchmark.reason) add('benchmark', 'BENCHMARK_NOTICE', '基准说明', text(benchmark.reason))
  for (const [source, values] of [['result', result.warnings], ['benchmark', benchmark.warnings], ['source', context.warnings]]) {
    for (const raw of list(values)) {
      const item = reason(raw)
      add(record(raw).source ? `${source}.${raw.source}` : source, item.code, item.message, text(raw))
    }
  }
  for (const key of ['modelRef', 'modelTaskId']) {
    const candidates = [['provenance', provenance[key]], ['result', result[key]], ['task', task[key]], ['request', context[key]]]
      .filter(([, value]) => known(value))
    if (new Set(candidates.map(([, value]) => String(value))).size > 1) {
      const source = candidates[0][0]
      add(`source.${source}.${key}`, 'SOURCE_REFERENCE_CONFLICT', '模型来源记录不一致，请核对本次冻结请求',
        candidates.map(([origin, value]) => `${origin}.${key}: ${text(value)}`).join('\n'))
    }
  }
  if (result.valuation?.dataState === 'INCOMPLETE') add('data', 'INCOMPLETE', '部分资产行情尚未到齐', text(result.valuation))
  assumptions.forEach((item, index) => { if (item.risk) add('assumptions', `LIMITATION_${index}`, item.text, item.original) })
  const curve = list(result.equityCurve).filter(p => p?.date)
  return {
    status, statusText: status === 'QUALIFIED' ? '验证通过' : status === 'UNQUALIFIED' ? '验证未通过'
      : status ? `验证状态：${status}` : task.status === 'SUCCEEDED' ? '未记录验证结论' : '尚无有效验证结论',
    reasons, qualification, scopeMeaning, warnings: [...warnings.values()], assumptions,
    assumptionSummary: assumptionParameters.filter(p => !['publicationLagDays', 'settlementDays', 'fundShareDecimals'].includes(p.key)),
    assumptionParameters, effectiveConfig, requestConfig: record(context.requestConfig),
    provenance, lineage: record(context.lineage), dataSnapshot: list(context.dataSnapshot),
    evaluationRange: { startDate: provenance.startDate, endDate: provenance.endDate },
    requestedRange: record(context.requestedRange),
    observedRange: { startDate: curve[0]?.date, endDate: curve.at(-1)?.date, observations: curve.length },
    benchmark, modelRef: provenance.modelRef ?? result.modelRef ?? task.modelRef ?? context.modelRef,
    modelTaskId: provenance.modelTaskId ?? context.modelTaskId,
    modelApplicable: String(effectiveConfig.strategyType || context.requestConfig?.strategyType || '').startsWith('ML_'),
  }
}

export function filterVersionBacktests(tasks, versions, revision, selection = 'current') {
  if (selection === 'all') return tasks
  const versionId = selection === 'current' ? versions.find(v => Number(v.version) === Number(revision))?.id : selection
  return versionId == null ? [] : tasks.filter(t => String(t.strategyVersionId) === String(versionId))
}
