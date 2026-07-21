package com.smartfinance.agent.wealth;

import java.math.BigDecimal;

public class WealthCalculator {

    public Result calculate(BigDecimal baselineNonInvestment,
                            BigDecimal incomeAfterBaseline,
                            BigDecimal expenseAfterBaseline,
                            BigDecimal netInvestmentTransfer,
                            BigDecimal investmentCash,
                            BigDecimal holdingMarketValue) {
        BigDecimal nonInvestment = zero(baselineNonInvestment)
                .add(zero(incomeAfterBaseline))
                .subtract(zero(expenseAfterBaseline))
                .subtract(zero(netInvestmentTransfer));
        BigDecimal investment = zero(investmentCash).add(zero(holdingMarketValue));
        return new Result(nonInvestment, investment, nonInvestment.add(investment));
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Result(BigDecimal dailyCash,
                         BigDecimal investmentTotal,
                         BigDecimal totalAssets) {
    }
}
