package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualityIssueMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualitySnapshotMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestmentDataQualityServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test
    void analysisGateSelectsItsWindowAndConfigButStillHonorsANewerBlockInThatWindow() throws Exception {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + temporary.resolve("quality.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/sqlite/V1__baseline.sql"))
                .execute(source);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.addMapper(InvestmentDataQualitySnapshotMapper.class);
        configuration.addMapper(InvestmentDataQualityIssueMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var snapshots = session.getMapper(InvestmentDataQualitySnapshotMapper.class);
        var issues = session.getMapper(InvestmentDataQualityIssueMapper.class);
        var runtime = new InvestmentRuntimeProperties();
        runtime.getDataQuality().setConfigVersion("config-v1");
        runtime.getDataQuality().setFrequency("DAY");
        runtime.getDataQuality().setStockAdjustType("NONE");
        var client = mock(AnalysisServiceClient.class);
        when(client.replayDataQuality(any(InvestmentProduct.class), anyString(), nullable(String.class), eq("config-v1")))
                .thenAnswer(call -> Map.of("qualityReport", Map.of("qualityRuleSetVersion", "rules-v1",
                        "status", "same-window-block".equals(call.getArgument(1)) ? "BLOCKED" : "PASS",
                        "decision", "same-window-block".equals(call.getArgument(1)) ? "BLOCK" : "ALLOW"),
                        "records", List.of(Map.of("data_date", "2026-06-30", "close", "10"))));
        var service = new InvestmentDataQualityService(snapshots, issues, client,
                runtime, new ObjectMapper().findAndRegisterModules());
        var product = new InvestmentProduct();
        product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");
        var current = saved("current", LocalDate.of(2026, 6, 1), "config-v1", "ALLOW", 1);
        snapshots.insert(current);
        snapshots.insert(saved("wide", LocalDate.of(2001, 1, 1), "config-v1", "BLOCK", 2));
        snapshots.insert(saved("other-config", LocalDate.of(2026, 6, 1), "config-v2", "BLOCK", 3));

        assertThat(service.latestStatus(product)).containsEntry("decision", "BLOCK");
        assertThat(service.analysisStatus(product, "current")).containsEntry("decision", "ALLOW")
                .containsEntry("datasetVersion", "current");
        assertThat(service.preparedEvaluation(product, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
                .datasetVersion()).isEqualTo("current");
        snapshots.insert(saved("same-window-block", LocalDate.of(2026, 6, 1), "config-v1", "BLOCK", 4));
        assertThat(service.analysisStatus(product, "current")).containsEntry("decision", "BLOCK")
                .containsEntry("datasetVersion", "same-window-block");
        assertThat(service.preparedEvaluation(product, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
                .blocked()).isTrue();
        assertThat(service.preparedEvaluation(product, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))).isNull();
        verify(client, never()).validateDataQuality(any(), any(), any(), any(), any(), any());
        assertThat(service.analysisStatus(product, "missing-dataset")).containsEntry("status", "NOT_EVALUATED")
                .doesNotContainKey("decision");
    }

    @Test
    void fundInputComparisonUsesTheStoredReinvestmentBasisAndDatabasePrecision() {
        var product = new InvestmentProduct(); product.setProductType("MUTUAL_FUND");
        var input = new com.smartfinance.agent.investment.entity.ProductDailyQuote();
        input.setTradeDate(LocalDate.of(2026, 6, 30));
        input.setClosePrice(new java.math.BigDecimal("1.2345"));
        var evaluation = new InvestmentDataQualityService.Evaluation(new InvestmentDataQualitySnapshot(), Map.of(),
                List.of(Map.of("data_date", "2026-06-30", "nav", "1.2345", "adjustment_factor", "1.3579177804652")), List.of());
        input.setTotalReturnIndex(new java.math.BigDecimal("1.2345").multiply(new java.math.BigDecimal("1.3579177804652"))
                .setScale(10, java.math.RoundingMode.HALF_UP));
        assertThat(evaluation.matchesInputs(product, List.of(input))).isTrue();
        input.setTotalReturnIndex(new java.math.BigDecimal("1.2345"));
        assertThat(evaluation.matchesInputs(product, List.of(input))).isFalse();
    }

    @Test
    void incrementalReceiptsAreRecheckedAsOneWindowAndListingBoundsAreRespected() {
        var snapshots = mock(InvestmentDataQualitySnapshotMapper.class);
        var client = mock(AnalysisServiceClient.class);
        var runtime = new InvestmentRuntimeProperties();
        runtime.getDataQuality().setConfigVersion("config-v1");
        runtime.getDataQuality().setStockAdjustType("NONE");
        var service = new InvestmentDataQualityService(snapshots, mock(InvestmentDataQualityIssueMapper.class),
                client, runtime, new ObjectMapper().findAndRegisterModules());
        var product = new InvestmentProduct();
        product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");
        product.setListingDate(LocalDate.of(2026, 6, 1));
        var base = saved("base", product.getListingDate(), "config-v1", "ALLOW", 1);
        base.setRequestedEndDate(LocalDate.of(2026, 6, 29));
        var tail = saved("tail", LocalDate.of(2026, 6, 30), "config-v1", "ALLOW", 2);
        when(snapshots.selectList(any())).thenReturn(List.of(tail, base));
        when(client.replayDataQuality(any(InvestmentProduct.class), anyString(), nullable(String.class), eq("config-v1")))
                .thenReturn(Map.of("qualityReport", Map.of("qualityRuleSetVersion", "rules-v1", "status", "PASS", "decision", "ALLOW"),
                        "records", List.of(Map.of("data_date", "2026-06-30", "close", "10"))));
        when(client.replayDataQuality(eq(product), eq(List.of("base", "tail")), eq(List.of()), eq("config-v1"),
                eq(product.getListingDate()), eq(LocalDate.of(2026, 6, 30))))
                .thenReturn(Map.of("datasetVersion", "whole", "manifest", Map.ofEntries(
                        Map.entry("productType", "STOCK"), Map.entry("code", "600000"), Map.entry("market", "SSE"),
                        Map.entry("frequency", "DAY"), Map.entry("adjustType", "NONE"), Map.entry("provider", "TEST"),
                        Map.entry("adapterVersion", "v1"), Map.entry("requestedStartDate", "2026-06-01"),
                        Map.entry("requestedEndDate", "2026-06-30"), Map.entry("sampleStartDate", "2026-06-01"),
                        Map.entry("sampleEndDate", "2026-06-30"), Map.entry("fetchedAt", "2026-07-02T00:00:00Z")),
                        "qualityReport", Map.of("qualityRuleSetVersion", "rules-v1", "status", "PASS", "decision", "ALLOW",
                                "enforcementMode", "ENFORCE", "evaluatedAt", "2026-07-02T00:00:00Z"),
                        "records", List.of(Map.of("data_date", "2026-06-30", "close", "10"))));

        assertThat(service.preparedEvaluation(product, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))
                .isNotNull().extracting(InvestmentDataQualityService.Evaluation::datasetVersion).isEqualTo("whole");
        verify(client, never()).validateDataQuality(any(), any(), any(), any(), any(), any());
        verify(client).claimDataQuality("whole", "config-v1");
        verify(snapshots).insert(any());
    }

    private static InvestmentDataQualitySnapshot saved(String dataset, LocalDate start, String config,
                                                       String decision, int day) {
        var value = new InvestmentDataQualitySnapshot();
        value.setDatasetVersion(dataset); value.setProductType("STOCK"); value.setCode("600000");
        value.setMarket("SSE"); value.setFrequency("DAY"); value.setAdjustType("NONE");
        value.setProvider("TEST"); value.setAdapterVersion("v1"); value.setQualityConfigVersion(config);
        value.setQualityRuleSetVersion("rules-v1"); value.setQualityStatus("ALLOW".equals(decision) ? "PASS" : "BLOCKED");
        value.setDecision(decision); value.setEnforcementMode("ENFORCE"); value.setRequestedStartDate(start);
        value.setRequestedEndDate(LocalDate.of(2026, 6, 30)); value.setSampleStartDate(start);
        value.setSampleEndDate(value.getRequestedEndDate());
        value.setFetchedAt(java.time.LocalDateTime.of(2026, 7, day, 0, 0)); value.setEvaluatedAt(value.getFetchedAt());
        value.setCreatedAt(value.getFetchedAt()); value.setUpdatedAt(value.getFetchedAt());
        value.setManifestJson("{}"); value.setReportJson("{}"); value.setSecondaryDatasetVersionsJson("[]");
        return value;
    }

    @Test
    void replayRuleDriftIsRejectedBeforeChangingTheStoredSnapshot() {
        var snapshots = mock(InvestmentDataQualitySnapshotMapper.class);
        var client = mock(AnalysisServiceClient.class);
        var runtime = new InvestmentRuntimeProperties();
        runtime.getDataQuality().setConfigVersion("config-v1");
        var service = new InvestmentDataQualityService(snapshots, mock(InvestmentDataQualityIssueMapper.class),
                client, runtime, new ObjectMapper().findAndRegisterModules());
        var stored = new InvestmentDataQualitySnapshot();
        stored.setDatasetVersion("saved-dataset");
        stored.setQualityRuleSetVersion("old-rule-set");
        when(snapshots.selectOne(any())).thenReturn(stored);
        when(client.replayDataQuality(any(InvestmentProduct.class), eq("saved-dataset"), isNull(), eq("config-v1")))
                .thenReturn(Map.of("qualityReport", Map.of("qualityRuleSetVersion", "new-rule-set",
                        "status", "PASS", "decision", "ALLOW"), "records", List.of()));
        var product = new InvestmentProduct();
        product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");

        assertThatThrownBy(() -> service.resolve(product, LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30), "NONE", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("规则版本发生漂移");
        assertThat(stored.getQualityRuleSetVersion()).isEqualTo("old-rule-set");
        verify(client, never()).validateDataQuality(any(), any(), any(), any(), any(), any());
        verify(snapshots, never()).updateById(any());
    }

    @Test
    void newerFullRepairReplacesAnOlderNarrowReceipt() {
        var snapshots = mock(InvestmentDataQualitySnapshotMapper.class);
        var client = mock(AnalysisServiceClient.class);
        var runtime = new InvestmentRuntimeProperties();
        runtime.getDataQuality().setConfigVersion("config-v1");
        var service = new InvestmentDataQualityService(snapshots, mock(InvestmentDataQualityIssueMapper.class),
                client, runtime, new ObjectMapper().findAndRegisterModules());
        var product = new InvestmentProduct(); product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");
        var old = saved("recent", LocalDate.of(2026, 6, 1), "config-v1", "ALLOW", 1);
        var repair = saved("repaired", LocalDate.of(2026, 1, 1), "config-v1", "ALLOW", 2);
        when(snapshots.selectList(any())).thenReturn(List.of(repair, old));
        when(client.replayDataQuality(any(InvestmentProduct.class), anyString(), nullable(String.class), eq("config-v1")))
                .thenReturn(Map.of("qualityReport", Map.of("qualityRuleSetVersion", "rules-v1", "status", "PASS", "decision", "ALLOW"),
                        "records", List.of(Map.of("data_date", "2026-06-30", "close", "11"))));

        assertThat(service.preparedEvaluation(product, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
                .datasetVersion()).isEqualTo("repaired");
        verify(client, never()).validateDataQuality(any(), any(), any(), any(), any(), any());
    }

    @Test
    void incrementalUpdateAfterFullRepairCannotReintroduceTheOldAdjustmentBasis() {
        var snapshots = mock(InvestmentDataQualitySnapshotMapper.class);
        var client = mock(AnalysisServiceClient.class);
        var runtime = new InvestmentRuntimeProperties(); runtime.getDataQuality().setConfigVersion("config-v1");
        var service = new InvestmentDataQualityService(snapshots, mock(InvestmentDataQualityIssueMapper.class),
                client, runtime, new ObjectMapper().findAndRegisterModules());
        var product = new InvestmentProduct(); product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");
        var old = saved("old-basis", LocalDate.of(2026, 6, 1), "config-v1", "ALLOW", 1);
        old.setRequestedEndDate(LocalDate.of(2026, 6, 29));
        var repair = saved("repaired-basis", LocalDate.of(2026, 1, 1), "config-v1", "ALLOW", 2);
        var tail = saved("new-tail", LocalDate.of(2026, 6, 30), "config-v1", "ALLOW", 3);
        tail.setRequestedEndDate(LocalDate.of(2026, 7, 1));
        for (var receipt : List.of(old, repair, tail)) receipt.setAdjustType("QFQ");
        when(snapshots.selectList(any())).thenReturn(List.of(tail, repair, old));
        when(client.replayDataQuality(any(InvestmentProduct.class), anyString(), nullable(String.class), eq("config-v1")))
                .thenReturn(Map.of("qualityReport", Map.of("qualityRuleSetVersion", "rules-v1", "status", "PASS", "decision", "ALLOW"),
                        "records", List.of(Map.of("data_date", "2026-06-30", "close", "11"))));
        when(client.replayDataQuality(any(), anyList(), anyList(), anyString(), any(), any()))
                .thenReturn(Map.of("datasetVersion", "whole", "manifest", Map.ofEntries(
                        Map.entry("productType", "STOCK"), Map.entry("code", "600000"), Map.entry("market", "SSE"),
                        Map.entry("frequency", "DAY"), Map.entry("adjustType", "QFQ"), Map.entry("provider", "TEST"),
                        Map.entry("adapterVersion", "v1"), Map.entry("requestedStartDate", "2026-06-01"),
                        Map.entry("requestedEndDate", "2026-07-01"), Map.entry("sampleStartDate", "2026-06-01"),
                        Map.entry("sampleEndDate", "2026-07-01"), Map.entry("fetchedAt", "2026-07-03T00:00:00Z")),
                        "qualityReport", Map.of("qualityRuleSetVersion", "rules-v1", "status", "PASS", "decision", "ALLOW",
                                "enforcementMode", "ENFORCE", "evaluatedAt", "2026-07-03T00:00:00Z"),
                        "records", List.of(Map.of("data_date", "2026-07-01", "close", "11"))));

        service.preparedEvaluation(product, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        verify(client).replayDataQuality(eq(product), eq(List.of("repaired-basis", "new-tail")), eq(List.of()),
                eq("config-v1"), eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 7, 1)));
        verify(client, never()).replayDataQuality(eq(product), eq("old-basis"), any(), anyString());
    }
}
