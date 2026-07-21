package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaperOrderCalculatorTest {
    @Test
    void stockBuyIsRoundedDownToConfiguredLot() {
        PaperOrderCalculator.OrderDraft draft = PaperOrderCalculator.calculate(
                "STOCK", "ADD", new BigDecimal("0.10"),
                new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("9.63"),
                100, 4, new BigDecimal("100"));

        assertThat(draft.side()).isEqualTo("BUY");
        assertThat(draft.quantity()).isEqualByComparingTo("1000");
    }

    @Test
    void reduceTargetsHalfOfCurrentPaperPosition() {
        PaperOrderCalculator.OrderDraft draft = PaperOrderCalculator.calculate(
                "STOCK", "REDUCE", BigDecimal.ZERO,
                new BigDecimal("100000"), new BigDecimal("2000"), new BigDecimal("10"),
                100, 4, new BigDecimal("100"));

        assertThat(draft.side()).isEqualTo("SELL");
        assertThat(draft.quantity()).isEqualByComparingTo("1000");
    }
}
