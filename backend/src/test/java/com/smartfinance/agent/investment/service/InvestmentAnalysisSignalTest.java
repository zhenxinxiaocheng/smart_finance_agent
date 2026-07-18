package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentAnalysisSignalTest {

    @Test
    void changedSignalInvalidatesStaleAiExplanationButKeepsCooldownTimestamp() {
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        LocalDateTime updatedAt = LocalDateTime.now().minusMinutes(10);
        snapshot.setSignalHash("old-signal");
        snapshot.setAiExplanation("旧技术结论的解释");
        snapshot.setAiUpdatedAt(updatedAt);

        boolean changed = InvestmentAnalysisServiceImpl.invalidateStaleExplanation(
                snapshot, "new-signal");

        assertThat(changed).isTrue();
        assertThat(snapshot.getSignalHash()).isEqualTo("new-signal");
        assertThat(snapshot.getAiExplanation()).isNull();
        assertThat(snapshot.getAiUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void unchangedSignalKeepsCachedAiExplanation() {
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setSignalHash("same-signal");
        snapshot.setAiExplanation("当前解释");

        boolean changed = InvestmentAnalysisServiceImpl.invalidateStaleExplanation(
                snapshot, "same-signal");

        assertThat(changed).isFalse();
        assertThat(snapshot.getAiExplanation()).isEqualTo("当前解释");
    }

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
    void missingStrategyScoreDoesNotFallBackToAFabricatedNeutralValue() {
        assertThat(InvestmentAnalysisServiceImpl.score(Map.of(
                "status", "INSUFFICIENT", "verdict", "WAIT"))).isNull();
        assertThat(InvestmentAnalysisServiceImpl.score(Map.of("score", 71.5)))
                .isEqualByComparingTo("71.5");
    }
}
