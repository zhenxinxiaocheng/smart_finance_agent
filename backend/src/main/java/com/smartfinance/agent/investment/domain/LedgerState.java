package com.smartfinance.agent.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record LedgerState(
        BigDecimal quantity,
        BigDecimal costAmount,
        BigDecimal realizedPnl,
        BigDecimal cashBalance
) {
    public static LedgerState empty() {
        return new LedgerState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal averageCost() {
        if (quantity.signum() == 0) {
            return BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);
        }
        return costAmount.divide(quantity, 10, RoundingMode.HALF_UP);
    }
}
