const finite = value => typeof value === 'number' && Number.isFinite(value)
const percent = (value, signed = false) => !finite(value) ? '—'
  : `${signed && value > 0 ? '+' : ''}${(value * 100).toFixed(2)}%`
const amount = value => finite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—'

export function buyAndHoldComparison(result = {}) {
  const baseline = result.buyAndHold
  if (!baseline) return null
  if (baseline.status !== 'READY') return {
    items: [], message: baseline.reason === 'BUY_AND_HOLD_ASSETS_NOT_FILLED'
      ? '部分资产未能完成买入，暂无法比较。' : '买入并持有对照暂不可用。',
  }
  const strategy = result.metrics || {}
  const hold = baseline.metrics || {}
  const strategyReturn = strategy.netReturn ?? strategy.totalReturn
  const holdReturn = hold.netReturn ?? hold.totalReturn
  const relative = finite(strategyReturn) && finite(holdReturn) ? strategyReturn - holdReturn : null
  const items = [
    { key: 'strategyReturn', label: '策略累计收益', value: percent(strategyReturn, true) },
    { key: 'holdReturn', label: '买入并持有累计收益', value: percent(holdReturn, true) },
    { key: 'relativeReturn', label: '相对买入并持有', value: percent(relative, true) },
    { key: 'strategyDrawdown', label: '策略最大回撤', value: percent(strategy.maxDrawdown) },
    { key: 'holdDrawdown', label: '买入并持有最大回撤', value: percent(hold.maxDrawdown) },
  ]
  for (const [prefix, name, metrics] of [['strategy', '策略', strategy], ['hold', '买入并持有', hold]]) {
    const annual = metrics.annualReturn ?? metrics.annualizedReturn
    const fees = metrics.fees ?? metrics.totalFees
    if (finite(annual)) items.push({ key: `${prefix}Annual`, label: `${name}年化收益`, value: percent(annual, true) })
    if (finite(fees)) items.push({ key: `${prefix}Fees`, label: `${name}费用`, value: amount(fees) })
  }
  return { items, message: '' }
}
