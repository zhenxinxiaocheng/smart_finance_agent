package com.smartfinance.agent.investment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvestmentLedgerCalculatorTest {

    private final InvestmentLedgerCalculator calculator = new InvestmentLedgerCalculator();

    @Test
    void buy_shouldIncreaseQuantityAndIncludeFeeInCost() {
        LedgerState result = calculator.apply(
                LedgerState.empty(),
                LedgerEvent.buy(new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("5"))
        );

        assertEquals(new BigDecimal("100"), result.quantity());
        assertEquals(new BigDecimal("1005"), result.costAmount());
        assertEquals(new BigDecimal("10.0500000000"), result.averageCost());
        assertEquals(new BigDecimal("-1005"), result.cashBalance());
    }

    @Test
    void sell_shouldUseMovingAverageCostAndDeductFeeFromProceeds() {
        LedgerState bought = calculator.apply(
                calculator.apply(LedgerState.empty(), LedgerEvent.buy(new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO)),
                LedgerEvent.buy(new BigDecimal("100"), new BigDecimal("12"), BigDecimal.ZERO)
        );

        LedgerState result = calculator.apply(
                bought,
                LedgerEvent.sell(new BigDecimal("50"), new BigDecimal("13"), new BigDecimal("5"))
        );

        assertEquals(new BigDecimal("150"), result.quantity());
        assertEquals(new BigDecimal("1650.0000000000"), result.costAmount());
        assertEquals(new BigDecimal("95.0000000000"), result.realizedPnl());
        assertEquals(new BigDecimal("-1555"), result.cashBalance());
    }

    @Test
    void sell_shouldRejectQuantityGreaterThanPosition() {
        LedgerState bought = calculator.apply(
                LedgerState.empty(),
                LedgerEvent.buy(new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO)
        );

        assertThrows(IllegalArgumentException.class, () -> calculator.apply(
                bought,
                LedgerEvent.sell(new BigDecimal("11"), new BigDecimal("12"), BigDecimal.ZERO)
        ));
    }

    @Test
    void dividendAndSplit_shouldPreserveCostAndUpdateCashOrQuantity() {
        LedgerState bought = calculator.apply(
                LedgerState.empty(),
                LedgerEvent.buy(new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO)
        );
        LedgerState dividend = calculator.apply(bought, LedgerEvent.dividend(new BigDecimal("80")));
        LedgerState split = calculator.apply(dividend, LedgerEvent.split(new BigDecimal("2")));

        assertEquals(new BigDecimal("200"), split.quantity());
        assertEquals(new BigDecimal("1000"), split.costAmount());
        assertEquals(new BigDecimal("80"), split.realizedPnl());
        assertEquals(new BigDecimal("-920"), split.cashBalance());
        assertEquals(new BigDecimal("5.0000000000"), split.averageCost());
    }
}
