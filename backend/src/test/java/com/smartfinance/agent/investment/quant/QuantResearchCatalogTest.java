package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantResearchCatalogTest {

    @Test
    void exposesFiveAssetSpecificModelFamilies() {
        List<Map<String, Object>> families = QuantResearchCatalog.modelFamilies();

        assertThat(families)
                .extracting(item -> item.get("code"))
                .containsExactly(
                        "A_SHARE_STOCK",
                        "INDEX_FUND",
                        "ACTIVE_FUND",
                        "QDII_INDEX_FUND",
                        "COMMODITY_FUND"
                );
    }

    @Test
    void parameterSchemaIsServerDrivenAndStrictValidationIsImmutable() {
        Map<String, Object> schema = QuantResearchCatalog.parameterSchema();

        assertThat(schema).containsEntry("validationMode", "STRICT");
        assertThat(((List<?>) schema.get("algorithms")).stream().map(String::valueOf).toList())
                .containsExactly(
                        "ELASTIC_NET",
                        "XGBOOST",
                        "EXTRA_TREES",
                        "TREND_VOLATILITY",
                        "RISK_FILTERED_MEAN_REVERSION",
                        "REGIME_ENSEMBLE"
                );
        assertThat((List<?>) schema.get("fields")).isNotEmpty();
    }

    @Test
    void experimentFingerprintIsStableAcrossMapOrdering() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("learningRate", 0.05);
        first.put("estimators", 48);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("estimators", 48);
        second.put("learningRate", 0.05);

        String firstFingerprint = QuantResearchCatalog.experimentFingerprint(
                7L, 12L, "A_SHARE_STOCK", "SHORT", first
        );
        String secondFingerprint = QuantResearchCatalog.experimentFingerprint(
                7L, 12L, "A_SHARE_STOCK", "SHORT", second
        );

        assertThat(firstFingerprint)
                .hasSize(64)
                .isEqualTo(secondFingerprint);
        assertThat(QuantResearchCatalog.experimentFingerprint(
                7L, 12L, "A_SHARE_STOCK", "SHORT", "ELASTIC_NET", first
        )).isNotEqualTo(QuantResearchCatalog.experimentFingerprint(
                7L, 12L, "A_SHARE_STOCK", "SHORT", "GRADIENT_BOOSTING", first
        ));
        assertThat(QuantResearchCatalog.experimentFingerprint(
                7L, 12L, 3L, "a".repeat(64),
                "A_SHARE_STOCK", "SHORT", "ELASTIC_NET", first
        )).isNotEqualTo(QuantResearchCatalog.experimentFingerprint(
                7L, 12L, 3L, "b".repeat(64),
                "A_SHARE_STOCK", "SHORT", "ELASTIC_NET", first
        ));
    }

    @Test
    void canonicalHashSupportsPointInTimeUniverseDates() {
        String fingerprint = QuantResearchCatalog.canonicalHash(Map.of(
                "validFrom", LocalDate.parse("2021-01-01"),
                "validTo", LocalDate.parse("2026-07-26")
        ));

        assertThat(fingerprint).hasSize(64);
    }
}
