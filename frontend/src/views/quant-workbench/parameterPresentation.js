export const parameterTitles = {
  lookback: '观察窗口（日）', slowWindow: '趋势慢均线（日）', topN: '最多选择标的数',
  rebalanceDays: '调仓间隔（日）', predictionHorizon: '预测周期（日）', initialCash: '回测初始资金',
  maxWeight: '单标的权重上限', maxDrawdown: '最大允许回撤', targetVol: '单标的降仓波动阈值',
  feeRate: '买入费率', sellFeeRate: '卖出费率', slippageBps: '滑点（基点）',
  publicationLagDays: '净值公布延迟（日）', settlementDays: '结算延迟（日）', seed: '随机种子',
}

export const percentageParameterKeys = ['maxWeight', 'maxDrawdown', 'targetVol', 'feeRate', 'sellFeeRate']
export const parameterTitle = key => parameterTitles[key] || key || '未记录参数'

export function parameterDisplayValue(key, value) {
  if (value == null || value === '') return '未记录'
  if (typeof value !== 'number') return String(value)
  if (percentageParameterKeys.includes(key)) return `${Number((value * 100).toFixed(6))}%`
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 8 })
}
