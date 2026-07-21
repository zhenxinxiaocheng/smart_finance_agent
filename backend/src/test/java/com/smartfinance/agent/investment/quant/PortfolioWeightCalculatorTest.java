package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioWeightCalculatorTest {
    @Test
    void calculatesCurrentWeightFromHoldingAndTotalWealth() {
        assertThat(PortfolioWeightCalculator.calculate(
                new BigDecimal("500"), new BigDecimal("9.63"), new BigDecimal("100000")))
                .isEqualByComparingTo("0.04815");
    }

    @Test
    void returnsZeroWhenWealthIsUnavailable() {
        assertThat(PortfolioWeightCalculator.calculate(BigDecimal.TEN, BigDecimal.TEN, null))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
