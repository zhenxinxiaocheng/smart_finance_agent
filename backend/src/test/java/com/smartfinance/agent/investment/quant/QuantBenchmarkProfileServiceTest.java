package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantBenchmarkProfileServiceTest {

    @Test
    void exactOfficialProfileWinsAndLoadsVersionedRecords() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfile fallback = profile(null, "INDEX_FUND", "CSI300");
        BenchmarkProfile exact = profile("010736", "INDEX_FUND", "CSI300_95_CASH_5");
        when(mapper.selectList(any())).thenReturn(List.of(fallback, exact));
        when(client.benchmarkHistory(
                "CSI300_95_CASH_5",
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-07-24")
        )).thenReturn(Map.of(
                "benchmarkCode", "CSI300_95_CASH_5",
                "records", List.of(Map.of("data_date", "2024-01-01", "close", 1.0))
        ));

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(mapper, client).resolve(
                        "MUTUAL_FUND",
                        "010736",
                        LocalDate.parse("2026-07-24"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2026-07-24")
                );

        assertThat(resolved.available()).isTrue();
        assertThat(resolved.benchmarkCode()).isEqualTo("CSI300_95_CASH_5");
        assertThat(resolved.modelFamily()).isEqualTo("INDEX_FUND");
        assertThat(resolved.records()).hasSize(1);
        assertThat(resolved.sourceVersion()).isEqualTo("OFFICIAL-2024-ANNUAL");
    }

    @Test
    void missingProfileIsExplicitlyUnavailableInsteadOfCashFallback() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(mapper, mock(AnalysisServiceClient.class)).resolve(
                        "STOCK",
                        "600519",
                        LocalDate.parse("2026-07-24"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2026-07-24")
                );

        assertThat(resolved.available()).isFalse();
        assertThat(resolved.failureCode()).isEqualTo("BENCHMARK_UNAVAILABLE");
        assertThat(resolved.benchmarkCode()).isNull();
        assertThat(resolved.records()).isEmpty();
    }

    private static BenchmarkProfile profile(String productCode,
                                            String modelFamily,
                                            String benchmarkCode) {
        BenchmarkProfile profile = new BenchmarkProfile();
        profile.setProductType("MUTUAL_FUND");
        profile.setProductCode(productCode);
        profile.setModelFamily(modelFamily);
        profile.setBenchmarkCode(benchmarkCode);
        profile.setSourceVersion("OFFICIAL-2024-ANNUAL");
        profile.setEffectiveFrom(LocalDate.parse("2021-01-01"));
        profile.setActive(true);
        return profile;
    }
}
