const finite = value => typeof value === 'number' && Number.isFinite(value)
const percent = (value, signed = false) => !finite(value) ? '—'
  : `${signed && value > 0 ? '+' : ''}${(value * 100).toFixed(2)}%`

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
  if (result.trackingIndex?.status === 'READY') {
    const index = result.trackingIndex
    items.push({ key: 'indexReturn', label: `跟踪指数 · ${index.name}累计收益`, value: percent(index.metrics?.netReturn, true) },
      { key: 'indexDrawdown', label: '跟踪指数最大回撤', value: percent(index.metrics?.maxDrawdown) })
  }
  return { items, message: '' }
}
