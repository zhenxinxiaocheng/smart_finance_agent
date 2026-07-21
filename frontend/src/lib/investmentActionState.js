function positive(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0
}

export function buildQuantityReferenceState({
  productType,
  suggestedBudget,
  sellQuantity,
  technicalConfidence,
  batches = [],
}) {
  const hasBudget = positive(suggestedBudget)
  const hasSell = positive(sellQuantity)
  const isFund = productType === 'MUTUAL_FUND'
  const hasExecutableBatch = batches.some(batch => positive(isFund ? batch?.amount : batch?.quantity))
  const belowBoardLot = !isFund && hasBudget && !hasExecutableBatch
  const showBatches = hasBudget && !belowBoardLot && hasExecutableBatch

  let emptyMessage = ''
  if (!showBatches && !hasSell) {
    if (belowBoardLot) {
      emptyMessage = '按 A 股 100 股一手取整后，当前建议预算不足 100 股，暂不生成买入批次。'
    } else if (positive(technicalConfidence)) {
      emptyMessage = '当前技术信号允许关注，但投资账户没有可用于换算的现金。'
    } else {
      emptyMessage = '当前技术评分没有形成明确的买入或减仓信号，建议继续观察。'
    }
  }

  return {
    showBatches,
    showBudget: showBatches,
    showSell: hasSell,
    emptyMessage,
  }
}

export function missingPriceZoneText({ hasLatestPrice, historyInsufficient }) {
  if (!hasLatestPrice) return '缺少最新价格'
  if (historyInsufficient) return '历史数据不足'
  return '当前未形成有效区间'
}
