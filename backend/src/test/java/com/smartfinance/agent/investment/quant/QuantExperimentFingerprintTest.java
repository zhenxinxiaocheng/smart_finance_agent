package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantExperimentFingerprintTest {

    @Test
    void experimentFingerprintIsStableAcrossMapOrdering() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("learningRate", 0.05);
        first.put("estimators", 48);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("estimators", 48);
        second.put("learningRate", 0.05);

        String firstFingerprint = QuantExperimentFingerprint.experiment(
                7L, 12L, "A_SHARE_STOCK", "WAVE", first
        );
        String secondFingerprint = QuantExperimentFingerprint.experiment(
                7L, 12L, "A_SHARE_STOCK", "WAVE", second
        );

        assertThat(firstFingerprint).hasSize(64).isEqualTo(secondFingerprint);
        assertThat(QuantExperimentFingerprint.experiment(
                7L, 12L, "A_SHARE_STOCK", "WAVE", "ELASTIC_NET", first
        )).isNotEqualTo(QuantExperimentFingerprint.experiment(
                7L, 12L, "A_SHARE_STOCK", "WAVE", "XGBOOST", first
        ));
    }

    @Test
    void horizonProfileVersionAndDaysPreventStaleExperimentReuse() {
        String first = QuantExperimentFingerprint.experiment(
                7L, 12L, 3L, "a".repeat(64), "A_SHARE_STOCK",
                "WAVE", "profile-v1", 37, "XGBOOST", Map.of()
        );
        String changedDays = QuantExperimentFingerprint.experiment(
                7L, 12L, 3L, "a".repeat(64), "A_SHARE_STOCK",
                "WAVE", "profile-v2", 45, "XGBOOST", Map.of()
        );

        assertThat(first).isNotEqualTo(changedDays);
    }

    @Test
    void canonicalHashSupportsPointInTimeUniverseDates() {
        String fingerprint = QuantExperimentFingerprint.canonicalHash(Map.of(
                "validFrom", LocalDate.parse("2021-01-01"),
                "validTo", LocalDate.parse("2026-07-26")
        ));

        assertThat(fingerprint).hasSize(64);
    }
}
