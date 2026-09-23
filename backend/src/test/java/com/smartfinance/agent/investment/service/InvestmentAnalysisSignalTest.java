package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentAnalysisSignalTest {

    @Test
    void quoteRefreshUsesTheResolvedProfileHistoryRequirement() {
        var properties = InvestmentSyncWorkerTest.horizonProperties(2500);
        assertThat(InvestmentAnalysisServiceImpl.calendarLookbackDays(20, properties)).isGreaterThan(20);
        assertThat(InvestmentAnalysisServiceImpl.calendarLookbackDays(900, properties)).isGreaterThan(900);
        assertThat(InvestmentAnalysisServiceImpl.shouldRefreshQuotes(320, 900, false)).isTrue();
        assertThat(InvestmentAnalysisServiceImpl.shouldRefreshQuotes(920, 900, false)).isFalse();
        assertThat(InvestmentAnalysisServiceImpl.shouldRefreshQuotes(920, 900, true)).isTrue();
    }

    @Test
    void interactiveHistoryIncludesTheBaselinePointForNDaysReturn() {
        var properties = InvestmentSyncWorkerTest.horizonProperties(2500);
        properties.setInteractiveHistoryMultiplier(1);
        properties.setIndicatorWarmupTradingDays(20);
        var profile = new ResolvedHorizonProfile(
                "asset:1", "template-v1", List.of(
                new HorizonSetting("CUSTOM", "自定义", 10, 20, 500, 500, true, "ASSET")
        ), List.of());

        assertThat(InvestmentAnalysisServiceImpl.interactiveHistoryDays(profile, properties))
                .isEqualTo(501);
    }

    @Test
    void horizonCacheMaterialIncludesTheActualTargetDays() {
        var profile = new ResolvedHorizonProfile(
                "asset:2", "template-v1", List.of(
                new HorizonSetting("SHORT", "短期", 10, 5, 20, 10, true, "ASSET")
        ), List.of());

        Map<String, Object> material = InvestmentAnalysisServiceImpl.horizonConfigMaterial(profile);

        assertThat(material).containsEntry("primaryHorizon", "SHORT");
        Map<?, ?> shortConfig = (Map<?, ?>) ((Map<?, ?>) material.get("horizons")).get("SHORT");
        assertThat(shortConfig.get("targetDays")).isEqualTo(10);
        assertThat(shortConfig.get("minDays")).isEqualTo(5);
        assertThat(shortConfig.get("maxDays")).isEqualTo(20);
    }

    @Test
    void staleSnapshotFromDifferentClassificationCannotBeUsedAsFallback() {
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setAnalysisStatus("READY");
        snapshot.setAnalysisCacheKey("old-classification-key");

        assertThat(InvestmentAnalysisServiceImpl.isCompatibleSnapshot(
                snapshot, "new-classification-key")).isFalse();
        assertThat(InvestmentAnalysisServiceImpl.isCompatibleSnapshot(
                snapshot, "old-classification-key")).isTrue();

        snapshot.setAnalysisStatus("INSUFFICIENT");
        assertThat(InvestmentAnalysisServiceImpl.isCompatibleSnapshot(
                snapshot, "old-classification-key")).isFalse();
    }

    @Test
    void missingStrategyScoreDoesNotFallBackToAFabricatedNeutralValue() {
        assertThat(InvestmentAnalysisServiceImpl.score(Map.of(
                "status", "INSUFFICIENT", "verdict", "WAIT"))).isNull();
        assertThat(InvestmentAnalysisServiceImpl.score(Map.of("score", 71.5)))
                .isEqualByComparingTo("71.5");
    }

    @Test
    void displayQuoteSeriesKeepsFullHistoryAndMergesIndicatorsByDate() {
        List<ProductDailyQuote> quotes = List.of(
                quote("2024-01-02", "10"),
                quote("2024-01-03", "11"),
                quote("2026-07-31", "12")
        );
        List<Map<String, Object>> analysisSeries = List.of(Map.of(
                "date", "2026-07-31",
                "nav", "12",
                "ma20", "11.5"
        ));

        List<Map<String, Object>> result = InvestmentAnalysisServiceImpl.displayQuoteSeries(
                quotes, analysisSeries);

        assertThat(result).hasSize(3);
        assertThat(result.get(0))
                .containsEntry("data_date", "2024-01-02")
                .doesNotContainKey("ma20");
        assertThat(result.get(2))
                .containsEntry("nav", "12")
                .containsEntry("ma20", "11.5");
    }

    @Test
    void fundDescriptiveResultRemainsInsufficientAndSuppressesAdvice() {
        Map<String, Object> result = Map.of(
                "status", "INSUFFICIENT",
                "adviceStatus", "UNAVAILABLE",
                "action", "WAIT"
        );

        assertThat(InvestmentAnalysisServiceImpl.analysisResultStatus(result))
                .isEqualTo("INSUFFICIENT");
        assertThat(InvestmentAnalysisServiceImpl.adviceUnavailable(
                "MUTUAL_FUND", result)).isTrue();
        assertThat(InvestmentAnalysisServiceImpl.adviceUnavailable(
                "STOCK", Map.of("status", "READY"))).isFalse();
    }

    private static ProductDailyQuote quote(String date, String close) {
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setTradeDate(LocalDate.parse(date));
        quote.setClosePrice(new BigDecimal(close));
        return quote;
    }
}
