package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuantBenchmarkProfileServiceTest {

    @Test
    void comparisonRetainsObservedHolidayStartingLevelWithoutFetching() {
        var mapper = mock(BenchmarkProfileMapper.class);
        var snapshots = mock(QuantBenchmarkSnapshotMapper.class);
        var client = mock(AnalysisServiceClient.class);
        var profile = profile("A", "INDEX_FUND", "CSI300");
        when(mapper.selectList(any())).thenReturn(List.of(profile));
        var snapshot = new QuantBenchmarkSnapshot();
        snapshot.setSnapshotVersion("frozen-v1");
        snapshot.setRecordsJson("[{\"data_date\":\"2022-12-29\",\"close\":99},{\"data_date\":\"2022-12-30\",\"close\":100},{\"data_date\":\"2023-01-03\",\"close\":110},{\"data_date\":\"2023-01-04\",\"close\":105}]");
        when(snapshots.selectOne(any())).thenReturn(snapshot);
        var resolved = new QuantBenchmarkProfileService(mapper,snapshots,client,new ObjectMapper())
                .resolveCachedComparison("MUTUAL_FUND","A",LocalDate.parse("2023-01-01"),LocalDate.parse("2023-01-04"));
        assertThat(resolved.available()).isTrue();
        assertThat(resolved.records()).hasSize(3);
        assertThat(resolved.records().get(0)).containsEntry("data_date","2022-12-30");
        verifyNoInteractions(client);
    }

    @Test
    void exactOfficialProfileWinsAndLoadsVersionedRecords() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        QuantBenchmarkSnapshotMapper snapshotMapper = mock(QuantBenchmarkSnapshotMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfile fallback = profile(null, "INDEX_FUND", "CSI300");
        BenchmarkProfile exact = profile("010736", "INDEX_FUND", "CSI300");
        when(mapper.selectList(any())).thenReturn(List.of(fallback, exact));
        when(client.benchmarkHistory(
                "CSI300",
                Map.of("CSI300", new java.math.BigDecimal("1.0")),
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2026-07-24")
        )).thenReturn(Map.of(
                "benchmarkCode", "CSI300",
                "provider", "TEST",
                "adapterVersion", "1",
                "records", List.of(
                        Map.of("data_date", "2024-01-01", "close", 1.0),
                        Map.of("data_date", "2026-07-24", "close", 1.2)
                )
        ));

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(
                        mapper,
                        snapshotMapper,
                        client,
                        new ObjectMapper()
                ).resolve(
                        "MUTUAL_FUND",
                        "010736",
                        LocalDate.parse("2026-07-24"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2026-07-24")
                );

        assertThat(resolved.available()).isTrue();
        assertThat(resolved.benchmarkCode()).isEqualTo("CSI300");
        assertThat(resolved.modelFamily()).isEqualTo("INDEX_FUND");
        assertThat(resolved.records()).hasSize(2);
        assertThat(resolved.sourceVersion()).hasSize(64);
    }

    @Test
    void missingProfileIsExplicitlyUnavailableInsteadOfCashFallback() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        QuantBenchmarkSnapshotMapper snapshotMapper = mock(QuantBenchmarkSnapshotMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(
                        mapper,
                        snapshotMapper,
                        mock(AnalysisServiceClient.class),
                        new ObjectMapper()
                ).resolve(
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

    @Test
    void legacyCompositeProfileIsIncompleteInsteadOfAssumingMissingCashReturnIsZero() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        QuantBenchmarkSnapshotMapper snapshotMapper = mock(QuantBenchmarkSnapshotMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfile exact = profile(
                "010736",
                "INDEX_FUND",
                "CSI300_95_CASH_5",
                "{\"CSI300\":0.95,\"CASH_CNY\":0.05}"
        );
        when(mapper.selectList(any())).thenReturn(List.of(exact));

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(
                        mapper,
                        snapshotMapper,
                        client,
                        new ObjectMapper()
                ).resolve(
                        "MUTUAL_FUND",
                        "010736",
                        LocalDate.parse("2026-07-24"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2026-07-24")
                );

        assertThat(resolved.available()).isFalse();
        assertThat(resolved.failureCode()).isEqualTo("BENCHMARK_INCOMPLETE");
        assertThat(resolved.failureSummary()).contains("复合基准");
        verifyNoInteractions(snapshotMapper, client);
    }

    @Test
    void persistedSnapshotIsReusedWithoutCallingNetworkProvider() {
        BenchmarkProfileMapper mapper = mock(BenchmarkProfileMapper.class);
        QuantBenchmarkSnapshotMapper snapshotMapper = mock(QuantBenchmarkSnapshotMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        BenchmarkProfile exact = profile("010736", "INDEX_FUND", "CSI300");
        QuantBenchmarkSnapshot snapshot = new QuantBenchmarkSnapshot();
        snapshot.setSnapshotVersion("a".repeat(64));
        snapshot.setBenchmarkProfileId(exact.getId());
        snapshot.setSampleStartDate(LocalDate.parse("2024-01-01"));
        snapshot.setSampleEndDate(LocalDate.parse("2026-07-24"));
        snapshot.setRecordsJson("""
                [
                  {"data_date":"2024-01-01","close":100},
                  {"data_date":"2026-07-24","close":120}
                ]
                """);
        when(mapper.selectList(any())).thenReturn(List.of(exact));
        when(snapshotMapper.selectOne(any())).thenReturn(snapshot);

        QuantBenchmarkProfileService.ResolvedBenchmark resolved =
                new QuantBenchmarkProfileService(
                        mapper,
                        snapshotMapper,
                        client,
                        new ObjectMapper()
                ).resolve(
                        "MUTUAL_FUND",
                        "010736",
                        LocalDate.parse("2026-07-24"),
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2026-07-24")
                );

        assertThat(resolved.available()).isTrue();
        assertThat(resolved.sourceVersion()).isEqualTo("a".repeat(64));
        verifyNoInteractions(client);
    }

    private static BenchmarkProfile profile(String productCode,
                                            String modelFamily,
                                            String benchmarkCode) {
        return profile(productCode, modelFamily, benchmarkCode, "{\"CSI300\":1.0}");
    }

    private static BenchmarkProfile profile(String productCode,
                                            String modelFamily,
                                            String benchmarkCode,
                                            String compositionJson) {
        BenchmarkProfile profile = new BenchmarkProfile();
        profile.setId(productCode == null ? 1L : 2L);
        profile.setProductType("MUTUAL_FUND");
        profile.setProductCode(productCode);
        profile.setModelFamily(modelFamily);
        profile.setBenchmarkCode(benchmarkCode);
        profile.setCompositionJson(compositionJson);
        profile.setSourceVersion("OFFICIAL-2024-ANNUAL");
        profile.setSourceUri("https://example.test/official");
        profile.setCurrency("CNY");
        profile.setFxRule("NONE");
        profile.setEffectiveFrom(LocalDate.parse("2021-01-01"));
        profile.setActive(true);
        return profile;
    }
}
