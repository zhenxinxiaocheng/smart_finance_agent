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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ALLOW,ALLOW", "BLOCK,ALLOW", "ALLOW,BLOCK", "BLOCK,BLOCK"})
    void legacyReceiptsAreRecheckedWithoutNewQuotesAndOldReportsRemainIntact(String oldDecision, String newDecision) throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 6, 30);
        var old = saved("legacy", start, "config-v1", oldDecision, 1);
        fixture.snapshots().insert(old);
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq(List.of("legacy")), eq(List.of()),
                eq("config-v2"), eq(start), eq(end))).thenReturn(upgradeResponse("upgraded", start, end, newDecision));
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq("upgraded"), isNull(), eq("config-v2")))
                .thenReturn(upgradeResponse("upgraded", start, end, newDecision));

        var upgraded = fixture.service().preparedEvaluation(fixture.product(), start, end);

        assertThat(upgraded).isNotNull();
        assertThat(upgraded.blocked()).isEqualTo("BLOCK".equals(newDecision));
        assertThat(upgraded.snapshot().getQualityConfigVersion()).isEqualTo("config-v2");
        assertThat(upgraded.ruleSetVersion()).isEqualTo("rules-v2");
        assertThat(fixture.snapshots().selectById(old.getId()).getDecision()).isEqualTo(oldDecision);
        assertThat(fixture.snapshots().selectById(old.getId()).getQualityRuleSetVersion()).isEqualTo("rules-v1");
        assertThat(fixture.snapshots().selectCount(null)).isEqualTo(2);
        assertThat(fixture.service().analysisStatus(fixture.product(), "upgraded")).containsEntry("decision", newDecision);
        fixture.service().preparedEvaluation(fixture.product(), start, end);
        assertThat(fixture.snapshots().selectCount(null)).isEqualTo(2);
        verify(fixture.client(), never()).validateDataQuality(any(), any(), any(), any(), any(), any());
        verify(fixture.client()).claimDataQuality("upgraded", "config-v2");
        verify(fixture.client(), never()).replayDataQuality(eq(fixture.product()), eq("legacy"), any(), anyString());
    }

    @Test
    void fetchingAValidatedBatchDoesNotPublishItAsStoredData() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 6, 30);
        when(fixture.client().validateDataQuality(fixture.product(), start, end, "DAY", "NONE", "config-v2"))
                .thenReturn(upgradeResponse("fetched-only", start, end, "ALLOW"));

        var fetched = fixture.service().resolve(fixture.product(), start, end, true);

        assertThat(fetched.datasetVersion()).isEqualTo("fetched-only");
        assertThat(fetched.snapshot().getId()).isNull();
        assertThat(fixture.snapshots().selectCount(null)).isZero();
        assertThat(fixture.service().preparedEvaluation(fixture.product(), start, end)).isNull();
    }

    @Test
    void legacyRecheckFailureCannotCreateANewApprovedReceipt() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1);
        fixture.snapshots().insert(saved("legacy", start, "config-v1", "ALLOW", 1));
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq(List.of("legacy")), eq(List.of()),
                eq("config-v2"), eq(start), eq(LocalDate.of(2026, 6, 30))))
                .thenThrow(new IllegalStateException("原始快照完整性校验失败"));

        assertThatThrownBy(() -> fixture.service().preparedEvaluation(fixture.product(), start, LocalDate.of(2026, 6, 30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("完整性");
        assertThat(fixture.snapshots().selectCount(null)).isEqualTo(1);
        assertThat(fixture.service().analysisStatus(fixture.product(), "legacy")).containsEntry("status", "NOT_EVALUATED");
        verify(fixture.client(), never()).claimDataQuality(anyString(), anyString());
    }

    @Test
    void aCurrentBlockedReceiptCannotBeReplacedByAnOlderApprovedReport() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1);
        fixture.snapshots().insert(saved("legacy", start, "config-v1", "ALLOW", 1));
        var current = saved("current-blocked", start, "config-v2", "BLOCK", 2);
        current.setQualityRuleSetVersion("rules-v2");
        fixture.snapshots().insert(current);
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq("current-blocked"), isNull(), eq("config-v2")))
                .thenReturn(upgradeResponse("current-blocked", start, LocalDate.of(2026, 6, 30), "BLOCK"));

        assertThat(fixture.service().preparedEvaluation(fixture.product(), start, LocalDate.of(2026, 6, 30)).blocked()).isTrue();
        verify(fixture.client(), never()).replayDataQuality(eq(fixture.product()), eq("legacy"), any(), anyString());
    }

    @Test
    void legacyHistoryAndACurrentIncrementAreCheckedAsACompleteWindow() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1);
        var end = LocalDate.of(2026, 6, 30);
        var base = saved("legacy-base", start, "config-v1", "ALLOW", 1);
        base.setRequestedEndDate(end.minusDays(1)); base.setSampleEndDate(end.minusDays(1));
        var tail = saved("current-tail", end, "config-v2", "ALLOW", 2);
        tail.setQualityRuleSetVersion("rules-v2");
        fixture.snapshots().insert(base); fixture.snapshots().insert(tail);
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq("current-tail"), isNull(), eq("config-v2")))
                .thenReturn(upgradeResponse("current-tail", end, end, "ALLOW"));
        when(fixture.client().replayDataQuality(eq(fixture.product()), eq(List.of("legacy-base", "current-tail")), eq(List.of()),
                eq("config-v2"), eq(start), eq(end))).thenReturn(upgradeResponse("combined", start, end, "ALLOW"));

        var result = fixture.service().preparedEvaluation(fixture.product(), start, end);
        assertThat(result).isNotNull();
        assertThat(result.datasetVersion()).isEqualTo("combined");
        verify(fixture.client()).replayDataQuality(eq(fixture.product()), eq(List.of("legacy-base", "current-tail")), eq(List.of()),
                eq("config-v2"), eq(start), eq(end));
        verify(fixture.client(), never()).validateDataQuality(any(), any(), any(), any(), any(), any());
    }

    private record UpgradeFixture(InvestmentDataQualityService service, InvestmentDataQualitySnapshotMapper snapshots,
                                  AnalysisServiceClient client, InvestmentProduct product, javax.sql.DataSource dataSource) { }

    @Test
    void incompatibleCollectionVersionsRequirePreparationEvenAfterLegacyRulesWereRechecked() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1); var end = LocalDate.of(2026, 6, 30);
        var base = saved("legacy", start, "config-v1", "ALLOW", 1);
        base.setRequestedEndDate(end.minusDays(1)); base.setAdapterVersion("1");
        fixture.snapshots().insert(base);
        assertThat(fixture.service().requiresPreparation(fixture.product(), start, end.minusDays(1), List.of())).isFalse();
        var tail = saved("tail", end, "config-v2", "ALLOW", 2);
        tail.setAdapterVersion("2"); tail.setQualityRuleSetVersion("rules-v2");
        fixture.snapshots().insert(tail);
        assertThat(fixture.service().requiresPreparation(fixture.product(), start, end, List.of())).isTrue();
        // Rechecking rules does not change the older collection units.
        base.setDatasetVersion("rechecked"); base.setId(null); base.setQualityConfigVersion("config-v2");
        base.setQualityRuleSetVersion("rules-v2"); fixture.snapshots().insert(base);
        assertThat(fixture.service().requiresPreparation(fixture.product(), start, end, List.of())).isTrue();
        var repaired = saved("repaired", start, "config-v2", "ALLOW", 3);
        repaired.setAdapterVersion("2"); repaired.setQualityRuleSetVersion("rules-v2");
        fixture.snapshots().insert(repaired);
        assertThat(fixture.service().requiresPreparation(fixture.product(), start, end, List.of())).isFalse();
        org.mockito.Mockito.verifyNoInteractions(fixture.client());
    }

    private UpgradeFixture upgradeFixture() throws Exception {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("upgrade.db"));
        source.setDriverClassName("org.sqlite.JDBC");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/sqlite/V1__baseline.sql")).execute(source);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
        global.setMetaObjectHandler(new com.smartfinance.agent.config.MyBatisPlusConfig());
        factory.setGlobalConfig(global);
        factory.setDataSource(source); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        configuration.addMapper(InvestmentDataQualitySnapshotMapper.class); configuration.addMapper(InvestmentDataQualityIssueMapper.class);
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var snapshots = session.getMapper(InvestmentDataQualitySnapshotMapper.class);
        var runtime = new InvestmentRuntimeProperties();
        runtime.getDataQuality().setConfigVersion("config-v2"); runtime.getDataQuality().setStockAdjustType("NONE");
        runtime.getDataQuality().setFrequency("DAY");
        var client = mock(AnalysisServiceClient.class);
        var service = new InvestmentDataQualityService(snapshots, session.getMapper(InvestmentDataQualityIssueMapper.class),
                client, runtime, new ObjectMapper().findAndRegisterModules());
        var product = new InvestmentProduct(); product.setProductType("STOCK"); product.setMarket("SSE"); product.setCode("600000");
        return new UpgradeFixture(service, snapshots, client, product, source);
    }

    private static Map<String, Object> upgradeResponse(String dataset, LocalDate start, LocalDate end, String decision) {
        return Map.of("datasetVersion", dataset, "manifest", Map.ofEntries(
                Map.entry("productType", "STOCK"), Map.entry("code", "600000"), Map.entry("market", "SSE"),
                Map.entry("frequency", "DAY"), Map.entry("adjustType", "NONE"), Map.entry("provider", "TEST"),
                Map.entry("adapterVersion", "v1"), Map.entry("requestedStartDate", start.toString()),
                Map.entry("requestedEndDate", end.toString()), Map.entry("sampleStartDate", start.toString()),
                Map.entry("sampleEndDate", end.toString()), Map.entry("fetchedAt", "2026-07-01T00:00:00Z")),
                "qualityReport", Map.of("qualityRuleSetVersion", "rules-v2", "status", "ALLOW".equals(decision) ? "PASS" : "BLOCKED",
                        "decision", decision, "enforcementMode", "ENFORCE", "evaluatedAt", "2026-07-03T00:00:00Z"),
                "records", List.of(Map.of("data_date", end.toString(), "close", "10")));
    }

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
    void missingOptionalSourceValuesDoNotBlockStoredPricesOrGetInventedInSnapshot() {
        var product=new InvestmentProduct();product.setProductType("STOCK");
        var input=new com.smartfinance.agent.investment.entity.ProductDailyQuote();input.setTradeDate(LocalDate.of(2026,6,30));
        input.setClosePrice(java.math.BigDecimal.TEN);input.setAmount(java.math.BigDecimal.TEN);input.setTurnoverRate(java.math.BigDecimal.ONE);
        var row=new java.util.LinkedHashMap<String,Object>();row.put("data_date","2026-06-30");row.put("close","10");row.put("amount",null);row.put("turnover_rate",null);
        var evaluation=new InvestmentDataQualityService.Evaluation(new InvestmentDataQualitySnapshot(),Map.of(),List.of(row),List.of());
        assertThat(evaluation.matchesInputs(product,List.of(input))).isTrue();
        assertThat(evaluation.records().get(0)).containsEntry("amount",null).containsEntry("turnover_rate",null);
        row.put("amount","11");assertThat(evaluation.matchesInputs(product,List.of(input))).isFalse();
    }

    @Test
    void actualAnalysisDatesCanUseIntactReceiptsAcrossEmptyWindowsAndEmptyTail() throws Exception {
        var fixture=upgradeFixture();var first=LocalDate.of(2026,6,1);var last=first.plusDays(10);
        var base=saved("base",first,"config-v1","ALLOW",1);base.setRequestedEndDate(first);fixture.snapshots().insert(base);
        var tail=saved("tail",last,"config-v1","ALLOW",2);tail.setRequestedEndDate(last);fixture.snapshots().insert(tail);
        var inputs=java.util.stream.Stream.of(first,last).map(day->{var q=new com.smartfinance.agent.investment.entity.ProductDailyQuote();q.setTradeDate(day);q.setClosePrice(java.math.BigDecimal.TEN);return q;}).toList();
        var response = new java.util.LinkedHashMap<String,Object>(upgradeResponse("actual",first,last,"ALLOW"));
        response.put("records",List.of(Map.of("data_date",first.toString(),"close","10"),Map.of("data_date",last.toString(),"close","10")));
        when(fixture.client().replayDataQuality(any(),anyList(),anyList(),anyString(),any(),any(),eq(List.of(first,last)))).thenReturn(response);
        var result=fixture.service().preparedEvaluation(fixture.product(),first.minusDays(10),last.plusDays(10),inputs);
        assertThat(result).isNotNull();
        assertThat(result.snapshot().getRequestedStartDate()).isEqualTo(first);
        assertThat(result.snapshot().getRequestedEndDate()).isEqualTo(last);
        assertThat(result.matchesInputs(fixture.product(),inputs)).isTrue();
        verify(fixture.client(),never()).validateDataQuality(any(),any(),any(),any(),any(),any());
    }

    @Test
    void storedObservationsWithoutPreparedReceiptsRequireRecoveryButEmptyQueriesDoNot() throws Exception {
        var fixture = upgradeFixture(); var day = LocalDate.of(2026, 6, 30);
        var input = new com.smartfinance.agent.investment.entity.ProductDailyQuote();
        input.setTradeDate(day); input.setClosePrice(java.math.BigDecimal.TEN);

        assertThat(fixture.service().requiresPreparation(fixture.product(), day, day, List.of(input))).isTrue();
        assertThat(fixture.service().requiresPreparation(fixture.product(), day, day, List.of())).isFalse();
        verifyNoInteractions(fixture.client());
    }

    @Test
    void sparseStoredObservationsNeedReceiptsOnlyForTheirActualDates() throws Exception {
        var fixture = upgradeFixture(); var first = LocalDate.of(2026, 6, 1); var last = first.plusDays(10);
        var base = saved("first", first, "config-v2", "ALLOW", 1); base.setRequestedEndDate(first);
        var tail = saved("last", last, "config-v2", "ALLOW", 2); tail.setRequestedEndDate(last);
        fixture.snapshots().insert(base); fixture.snapshots().insert(tail);
        var inputs = java.util.stream.Stream.of(first, last).map(day -> {
            var row = new com.smartfinance.agent.investment.entity.ProductDailyQuote(); row.setTradeDate(day); return row;
        }).toList();

        assertThat(fixture.service().requiresPreparation(fixture.product(), first, last, inputs)).isFalse();
        verifyNoInteractions(fixture.client());
    }

    @Test
    void preparedReceiptSharesTheQuoteTransactionAndRollsBackOnFailure() throws Exception {
        var fixture = upgradeFixture();
        var start = LocalDate.of(2026, 6, 1); var end = LocalDate.of(2026, 6, 30);
        when(fixture.client().validateDataQuality(fixture.product(), start, end, "DAY", "NONE", "config-v2"))
                .thenReturn(upgradeResponse("stored", start, end, "ALLOW"));
        var fetched = fixture.service().resolve(fixture.product(), start, end, true);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(fixture.dataSource());
        jdbc.execute("CREATE TABLE transaction_quotes (close_price NUMERIC NOT NULL)");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.dataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO transaction_quotes VALUES (10)");
            fixture.service().persistPrepared(fetched);
            throw new IllegalStateException("commit failed");
        })).hasMessageContaining("commit failed");
        assertThat(fixture.snapshots().selectCount(null)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_quotes", Integer.class)).isZero();

        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO transaction_quotes VALUES (10)");
            fixture.service().persistPrepared(fetched);
        });
        assertThat(fixture.snapshots().selectCount(null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_quotes", Integer.class)).isEqualTo(1);
    }

    @Test
    void anUnappliedLegacyBatchCannotCreateAnotherPreparedReceipt() throws Exception {
        var fixture = upgradeFixture(); var day = LocalDate.of(2026, 6, 30);
        fixture.snapshots().insert(saved("legacy-unapplied", day, "config-v1", "ALLOW", 1));
        var input = new com.smartfinance.agent.investment.entity.ProductDailyQuote();
        input.setTradeDate(day); input.setClosePrice(java.math.BigDecimal.ONE);
        when(fixture.client().replayDataQuality(any(), anyList(), anyList(), anyString(), any(), any(), eq(List.of(day))))
                .thenReturn(upgradeResponse("unapplied-derived", day, day, "ALLOW"));

        assertThatThrownBy(() -> fixture.service().preparedEvaluation(fixture.product(), day, day, List.of(input)))
                .hasMessageContaining("已保存行情不一致");
        assertThat(fixture.snapshots().selectCount(null)).isEqualTo(1);
        verify(fixture.client(), never()).claimDataQuality(anyString(), anyString());
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
