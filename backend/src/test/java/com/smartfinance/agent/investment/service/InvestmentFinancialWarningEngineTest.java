package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentFinancialWarningEngineTest {

    @Test
    void createsOnlyEvidenceBackedWarningsWithTraceableProvenance() {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getRisk().setEmergencyReserveMonths(new BigDecimal("6"));
        properties.getRisk().setAssetConcentrationWarningPercent(new BigDecimal("30"));
        properties.getRisk().setConservativeStrongScore(new BigDecimal("70"));
        properties.getRisk().setAggressiveWeakScore(new BigDecimal("35"));
        properties.getRisk().setVolatilityWarningPercent(new BigDecimal("25"));
        properties.getRisk().setDrawdownWarningPercent(new BigDecimal("20"));
        properties.getRisk().setMinimumTurnoverRatePercent(new BigDecimal("0.1"));
        properties.getRisk().setPortfolioRuleVersion("warning-rules-v2");
        InvestmentFinancialWarningEngine engine =
                new InvestmentFinancialWarningEngine(properties);
        WealthOverviewResponse wealth = new WealthOverviewResponse();
        wealth.setInitialized(true);
        wealth.setDailyCash(new BigDecimal("10000"));
        wealth.setTotalAssets(new BigDecimal("100000"));
        FinancialProfile profile = new FinancialProfile();
        profile.setFixedExpense(new BigDecimal("5000"));

        List<Map<String, Object>> warnings = engine.evaluate(
                new InvestmentFinancialWarningEngine.Input(
                        12L, "测试基金", new BigDecimal("45000"),
                        new BigDecimal("0.02"), wealth, profile,
                        new BigDecimal("50"), 31.0, -24.0,
                        "BLOCK", "MEDIUM", "dataset-v3"
                )
        );

        assertThat(warnings)
                .extracting(item -> item.get("code"))
                .contains(
                        "RESERVE_LOW",
                        "CONCENTRATION_HIGH",
                        "VOLATILITY_HIGH",
                        "DRAWDOWN_HIGH",
                        "LIQUIDITY_LOW",
                        "DATA_INCOMPLETE"
                )
                .doesNotContain("REFERENCE_ONLY");
        assertThat(warnings).allSatisfy(warning -> {
            assertThat(warning).containsEntry("sourceType", "RULE_ENGINE");
            @SuppressWarnings("unchecked")
            Map<String, Object> provenance =
                    (Map<String, Object>) warning.get("provenance");
            assertThat(provenance)
                    .containsKeys(
                            "assetId",
                            "horizonCode",
                            "datasetVersion",
                            "calculatedAt"
                    );
        });
    }
}
