function amount(value) {
  return Number(value || 0)
}

export function buildWealthStatistics(wealth = {}, totalIncome = 0, totalExpense = 0, transactionCount = 0) {
  const income = amount(totalIncome)
  const expense = amount(totalExpense)
  const initialized = Boolean(wealth.initialized)

  return {
    initialized,
    cards: [
      { key: 'totalAssets', label: '总资产', value: initialized ? amount(wealth.totalAssets) : null, desc: '日常现金与投资资产合计' },
      { key: 'dailyCash', label: '日常现金', value: initialized ? amount(wealth.dailyCash) : null, desc: '现金基准结合后续收支' },
      { key: 'investmentTotal', label: '投资资产', value: amount(wealth.investmentTotal), desc: '投资现金与持仓当前市值' },
      { key: 'periodBalance', label: '近七日结余', value: income - expense, desc: '近七日收入减支出' }
    ],
    composition: [
      { key: 'dailyCash', label: '日常现金', value: initialized ? amount(wealth.dailyCash) : null },
      { key: 'investmentCash', label: '投资账户现金', value: amount(wealth.investmentCash) },
      { key: 'holdingMarketValue', label: '股票基金当前市值', value: amount(wealth.holdingMarketValue) }
    ],
    activity: [
      { key: 'income', label: '近七日收入', value: income, type: 'money' },
      { key: 'expense', label: '近七日支出', value: expense, type: 'money' },
      { key: 'count', label: '近七日交易', value: Number(transactionCount || 0), type: 'count' }
    ]
  }
}
