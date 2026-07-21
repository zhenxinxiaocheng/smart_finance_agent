package com.smartfinance.agent.wealth;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WealthCalculatorTest {

    @Test
    void calculate_shouldTreatInvestmentTransfersAsInternalMovement() {
        WealthCalculator calculator = new WealthCalculator();

        var result = calculator.calculate(
                new BigDecimal("80000"),
                new BigDecimal("12000"),
                new BigDecimal("5000"),
                new BigDecimal("10000"),
                new BigDecimal("3000"),
                new BigDecimal("22000")
        );

        assertThat(result.dailyCash()).isEqualByComparingTo("77000");
        assertThat(result.investmentTotal()).isEqualByComparingTo("25000");
        assertThat(result.totalAssets()).isEqualByComparingTo("102000");
    }
}
