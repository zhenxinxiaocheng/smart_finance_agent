package com.smartfinance.agent.investment.quant;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class PortfolioWeightCalculator {
    private PortfolioWeightCalculator() {
    }

    static BigDecimal calculate(BigDecimal quantity, BigDecimal latestPrice, BigDecimal totalWealth) {
        if (quantity == null || latestPrice == null || totalWealth == null || totalWealth.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return quantity.max(BigDecimal.ZERO)
                .multiply(latestPrice.max(BigDecimal.ZERO))
                .divide(totalWealth, 8, RoundingMode.HALF_UP);
    }
}
