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
        properties.getRisk().setEmergencyReserveMonths(new BigDecimal("4"));
        properties.getRisk().setAssetConcentrationWarningPercent(new BigDecimal("25"));
        properties.getRisk().setPortfolioConcentrationWarningRatio(new BigDecimal("0.25"));
        properties.getRisk().setConservativeStrongScore(new BigDecimal("70"));
        properties.getRisk().setAggressiveWeakScore(new BigDecimal("40"));
        properties.getRisk().setVolatilityWarningPercent(new BigDecimal("25"));
        properties.getRisk().setDrawdownWarningPercent(new BigDecimal("20"));
        properties.getRisk().setMinimumTurnoverRatePercent(new BigDecimal("0.1"));
        properties.getRisk().setPortfolioRuleVersion("portfolio-test-v1");
        properties.getSync().setInitialDelayMs(500);
        properties.getSync().setPollDelayMs(1000);
        properties.getSync().setBatchLimit(7);
        properties.getSync().setErrorMessageMaxLength(300);
        properties.getSync().setFxLookbackCalendarDays(21);
        properties.getMarket().setZone(ZoneId.of("Asia/Shanghai"));
        properties.getMarket().setStockRefreshIntervalMs(3000);
        properties.getMarket().setStockActiveFreshnessMs(2500);
        properties.getMarket().setStockRefreshStart(LocalTime.of(9, 30));
        properties.getMarket().setStockRefreshEnd(LocalTime.of(14, 55));
        properties.getMarket().setFundInitialDelayMs(1000);
        properties.getMarket().setFundRefreshIntervalMs(60000);
        properties.getMarket().setFundActiveFreshnessMs(20000);
        properties.getMarket().setActiveRefreshConcurrency(4);
        properties.getMarket().setFundRefreshStart(LocalTime.of(7, 30));
        properties.getMarket().setFundRefreshEnd(LocalTime.of(22, 30));
        properties.getMarket().setCalendarCacheHours(6);
        properties.getMarket().setCalendarSearchLimitDays(500);
        properties.getMarket().setFallbackClosedDates(List.of(LocalDate.of(2026, 3, 2)));
        properties.getApi().setProductSearchLimit(30);
        properties.getApi().setDefaultTransactionLimit(50);
        properties.getApi().setMaxTransactionLimit(150);
        properties.getApi().setImportMaxBytes(1024);
        properties.getPlan().setExecutionDayMaximums(Map.of("DAILY", 1, "WEEKLY", 7, "MONTHLY", 28));
        properties.getDataQuality().setConfigVersion("data-quality-test");
        properties.getDataQuality().setFrequency("DAY");
        properties.getDataQuality().setStockAdjustType("QFQ");
        properties.getDataQuality().setFundAdjustType("NONE");
        properties.getDataQuality().setRealtimeAdjustType("NONE");
        properties.getAnalysis().setStrategyVersion("technical-strategy-test");

        properties.validate();

        assertThat(properties.getParameterVersion()).isEqualTo("investment-runtime-test");
        assertThat(properties.getRisk().getAssetConcentrationWarningPercent())
                .isEqualByComparingTo("25");
        assertThat(properties.getSync().getFxLookbackCalendarDays()).isEqualTo(21);
        assertThat(properties.getMarket().getFallbackClosedDates())
                .containsExactly(LocalDate.of(2026, 3, 2));
        assertThat(properties.getMarket().getStockActiveFreshnessMs()).isEqualTo(2500);
        assertThat(properties.getMarket().getFundActiveFreshnessMs()).isEqualTo(20000);
        assertThat(properties.getMarket().getActiveRefreshConcurrency()).isEqualTo(4);
    }
}
