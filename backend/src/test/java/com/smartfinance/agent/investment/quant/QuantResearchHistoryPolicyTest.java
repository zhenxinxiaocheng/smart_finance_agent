package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantResearchHistoryPolicyTest {

    @Test
    void configuredResearchHistoryMayStartBeforeTargetFundInception() {
        LocalDate resolved = QuantResearchHistoryPolicy.resolveStartDate(
                Map.of("historyStartDate", "2005-04-08"),
                LocalDate.of(2021, 1, 1),
                LocalDate.of(2021, 1, 4)
        );

        assertThat(resolved).isEqualTo(LocalDate.of(2005, 4, 8));
    }

    @Test
    void missingResearchHistoryFallsBackToEarliestKnownDate() {
        LocalDate resolved = QuantResearchHistoryPolicy.resolveStartDate(
                Map.of(),
                LocalDate.of(2013, 7, 18),
                LocalDate.of(2015, 1, 5)
        );

        assertThat(resolved).isEqualTo(LocalDate.of(2013, 7, 18));
    }

    @Test
    void changedHistoryRuleInvalidatesPreparedUniverse() {
        Map<String, Object> stored = Map.of(
                "benchmarkCode", "CSI300_95_CASH_5",
                "benchmarkSourceVersion", "OFFICIAL-2024-ANNUAL",
                "historyStartDate", "2021-01-01",
                "memberLimit", 12
        );
        Map<String, Object> configured = Map.of(
                "historyStartDate", "2005-04-08",
                "memberLimit", 20
        );

        assertThat(QuantResearchUniverseRuleVersion.matches(
                stored,
                configured,
                "CSI300_95_CASH_5",
                "OFFICIAL-2024-ANNUAL"
        )).isFalse();
    }

    @Test
    void memberQualityWindowStartsAtFundInceptionInsteadOfIndexHistoryStart() {
        LocalDate resolved = QuantResearchHistoryPolicy.memberStartDate(
                LocalDate.of(2005, 4, 8),
                List.of(
                        Map.of("data_date", "2020-01-02", "nav", "1.0"),
                        Map.of("data_date", "2020-01-03", "nav", "1.1")
                )
        );

        assertThat(resolved).isEqualTo(LocalDate.of(2020, 1, 2));
    }
}
