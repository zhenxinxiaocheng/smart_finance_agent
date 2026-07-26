export function actualInvestedAmount(asset) {
  if (asset?.quantity == null || asset?.averageCost == null) return null
  return Math.round(Number(asset.quantity) * Number(asset.averageCost) * 100000000) / 100000000
}

export function nextExecutionDate(frequency, executionDay, today = new Date()) {
  const date = new Date(today)
  date.setHours(12, 0, 0, 0)
  const day = Number(executionDay)

  if (frequency === 'DAILY') {
    // 每日计划从当天开始，无需额外计算执行日。
  } else if (frequency === 'WEEKLY') {
    const currentDay = date.getDay() || 7
    date.setDate(date.getDate() + ((day - currentDay + 7) % 7))
  } else {
    const currentDate = date.getDate()
    if (day < currentDate) date.setMonth(date.getMonth() + 1)
    date.setDate(day)
  }

  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const calendarDay = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${calendarDay}`
}

export function buildInvestmentPlanPayload(asset, form, today = new Date()) {
  const frequency = form.frequency
  const executionDay = frequency === 'DAILY' ? 1 : Number(form.executionDay)
  return {
    accountId: asset.accountId,
    product: {
      productType: asset.productType,
      market: asset.market,
      code: asset.code,
      name: asset.name,
      currency: asset.currency
    },
    amount: Number(form.amount),
    currency: asset.currency,
    frequency,
    executionDay,
    nextExecutionDate: form.nextExecutionDate || nextExecutionDate(frequency, executionDay, today)
  }
}
