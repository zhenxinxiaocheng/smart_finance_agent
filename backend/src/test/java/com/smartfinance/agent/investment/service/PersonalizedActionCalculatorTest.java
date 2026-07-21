package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalizedActionCalculatorTest {

    @Test
    void stockBudget_shouldUseTechnicalConfidenceAndRoundEachBatchToBoardLots() {
        PersonalizedActionCalculator calculator = calculator();

        var result = calculator.calculate("STOCK", new BigDecimal("75"), new BigDecimal("10"),
                new BigDecimal("12000"), new BigDecimal("500"));

        assertThat(result.technicalConfidence()).isEqualByComparingTo("0.5");
        assertThat(result.suggestedBudget()).isEqualByComparingTo("6000.0");
        assertThat(result.batches()).hasSize(3);
        assertThat(result.batches()).allSatisfy(batch ->
                assertThat(batch.quantity()).isEqualByComparingTo("200"));
        assertThat(result.sellQuantity()).isZero();
    }

    @Test
    void bearishStock_shouldLimitAndRoundSellQuantityWithoutChangingTechnicalScore() {
        PersonalizedActionCalculator calculator = calculator();

        var result = calculator.calculate("STOCK", new BigDecimal("25"), new BigDecimal("10"),
                new BigDecimal("12000"), new BigDecimal("550"));

        assertThat(result.technicalScore()).isEqualByComparingTo("25");
        assertThat(result.sellQuantity()).isEqualByComparingTo("200");
        assertThat(result.sellQuantity()).isLessThanOrEqualTo(new BigDecimal("550"));
    }

    @Test
    void fundAdvice_shouldReturnMoneyInsteadOfBoardLotQuantity() {
        PersonalizedActionCalculator calculator = calculator();

        var result = calculator.calculate("MUTUAL_FUND", new BigDecimal("75"), new BigDecimal("1.5"),
                new BigDecimal("12000"), new BigDecimal("1000"));

        assertThat(result.batches()).allSatisfy(batch -> {
            assertThat(batch.amount()).isEqualByComparingTo("2000.0");
            assertThat(batch.quantity()).isNull();
        });
    }

    @Test
    void personalizedActionShouldUseConfiguredScoreCenterBatchCountAndBoardLot() {
        InvestmentRuntimeProperties properties = runtimeProperties();
        properties.getAction().setScoreCenter(new BigDecimal("60"));
        properties.getAction().setScoreDistance(new BigDecimal("20"));
        properties.getAction().setBatchCount(2);
        properties.getAction().setStockBoardLotSize(new BigDecimal("50"));
        PersonalizedActionCalculator calculator = new PersonalizedActionCalculator(properties);

        var result = calculator.calculate("STOCK", new BigDecimal("70"), new BigDecimal("5"),
                new BigDecimal("2000"), BigDecimal.ZERO);

        assertThat(result.technicalConfidence()).isEqualByComparingTo("0.5");
        assertThat(result.batches()).hasSize(2);
        assertThat(result.batches()).allSatisfy(batch ->
                assertThat(batch.quantity()).isEqualByComparingTo("100"));
    }

    private static PersonalizedActionCalculator calculator() {
        return new PersonalizedActionCalculator(runtimeProperties());
    }

    static InvestmentRuntimeProperties runtimeProperties() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getAction().setScoreCenter(new BigDecimal("50"));
        properties.getAction().setScoreDistance(new BigDecimal("50"));
        properties.getAction().setBatchCount(3);
        properties.getAction().setStockBoardLotSize(new BigDecimal("100"));
        properties.getAction().setFundQuantityScale(4);
        return properties;
    }
}
