package com.smartfinance.agent.investment.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentRuntimePropertiesTest {

    @Test
    void runtimeBusinessRulesShouldBeRepresentedByOneVersionedConfiguration() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.setParameterVersion("investment-runtime-test");
        properties.getAction().setScoreCenter(new BigDecimal("60"));
        properties.getAction().setScoreDistance(new BigDecimal("20"));
        properties.getAction().setBatchCount(2);
        properties.getAction().setStockBoardLotSize(new BigDecimal("50"));
        properties.getAction().setFundQuantityScale(4);
        properties.getRisk().setEmergencyReserveMonths(new BigDecimal("4"));
        properties.getRisk().setAssetConcentrationWarningPercent(new BigDecimal("25"));
        properties.getRisk().setPortfolioConcentrationWarningRatio(new BigDecimal("0.25"));
        properties.getRisk().setConservativeStrongScore(new BigDecimal("70"));
        properties.getRisk().setAggressiveWeakScore(new BigDecimal("40"));
        properties.getRisk().setPortfolioRuleVersion("portfolio-test-v1");
        properties.getSync().setInitialDelayMs(500);
        properties.getSync().setPollDelayMs(1000);
        properties.getSync().setBatchLimit(7);
        properties.getSync().setErrorMessageMaxLength(300);
        properties.getSync().setFxLookbackCalendarDays(21);
        properties.getMarket().setZone(ZoneId.of("Asia/Shanghai"));
        properties.getMarket().setStockRefreshIntervalMs(3000);
        properties.getMarket().setStockRefreshStart(LocalTime.of(9, 30));
        properties.getMarket().setStockRefreshEnd(LocalTime.of(14, 55));
        properties.getMarket().setFundInitialDelayMs(1000);
        properties.getMarket().setFundRefreshIntervalMs(60000);
        properties.getMarket().setFundRefreshStart(LocalTime.of(7, 30));
        properties.getMarket().setFundRefreshEnd(LocalTime.of(22, 30));
        properties.getMarket().setCalendarCacheHours(6);
        properties.getMarket().setCalendarSearchLimitDays(500);
        properties.getMarket().setFallbackClosedDates(List.of(LocalDate.of(2026, 3, 2)));
        properties.getAi().setCooldownMinutes(45);
        properties.getAi().setMinimumParagraphs(2);
        properties.getAi().setMaximumParagraphs(4);
        properties.getAi().setMaxExplanationCharacters(2000);
        properties.getAi().setMaxInputJsonCharacters(3000);
        properties.getApi().setProductSearchLimit(30);
        properties.getApi().setDefaultTransactionLimit(50);
        properties.getApi().setMaxTransactionLimit(150);
        properties.getApi().setImportMaxBytes(1024);
        properties.getPlan().setExecutionDayMaximums(Map.of("DAILY", 1, "WEEKLY", 7, "MONTHLY", 28));

        properties.validate();

        assertThat(properties.getParameterVersion()).isEqualTo("investment-runtime-test");
        assertThat(properties.getAction().getBatchCount()).isEqualTo(2);
        assertThat(properties.getRisk().getAssetConcentrationWarningPercent())
                .isEqualByComparingTo("25");
        assertThat(properties.getSync().getFxLookbackCalendarDays()).isEqualTo(21);
        assertThat(properties.getMarket().getFallbackClosedDates())
                .containsExactly(LocalDate.of(2026, 3, 2));
    }
}
