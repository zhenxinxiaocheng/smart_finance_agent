package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAnalysisSnapshotMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.quant.QuantBenchmarkPreparationService;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestmentDataJobWorkerTest {

    @org.junit.jupiter.api.BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "detail-cache-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                InvestmentAnalysisSnapshot.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                com.smartfinance.agent.investment.entity.InvestmentAsset.class);
    }

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 10, 0);

    private InvestmentDataJobService jobService;
    private InvestmentAnalysisService analysisService;
    private InvestmentDataJobWorker worker;
    private QuantBenchmarkPreparationService benchmarkPreparationService;
    private InvestmentHistoryPreparationService historyPreparationService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jobService = mock(InvestmentDataJobService.class);
        analysisService = mock(InvestmentAnalysisService.class);
        benchmarkPreparationService = mock(QuantBenchmarkPreparationService.class);
        historyPreparationService = mock(InvestmentHistoryPreparationService.class);
        when(jobService.markSucceeded(any(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(jobService.recordCoverage(any(), anyString(), any()))
                .thenReturn(true);
        InvestmentHorizonProperties horizonProperties = new InvestmentHorizonProperties();
        horizonProperties.setMinimumHistoryTradingDays(20);
        horizonProperties.setMaxHistoryTradingDays(2500);
        horizonProperties.setHistoryMultiplier(3);
        horizonProperties.setInteractiveHistoryMultiplier(1);
        horizonProperties.setIndicatorWarmupTradingDays(250);
        horizonProperties.setCalendarDaysPerYear(365);
        horizonProperties.setTradingDaysPerYear(240);
        horizonProperties.setCalendarBufferDays(30);
        clock = new MutableClock(Instant.parse("2026-07-22T02:00:00Z"), SHANGHAI);
        worker = new InvestmentDataJobWorker(
                jobService,
                analysisService,
                horizonProperties,
                clock,
                benchmarkPreparationService,
                historyPreparationService
        );
        when(historyPreparationService.prepare(any())).thenReturn(prepared(20));

    }

    @Test
    void scheduledScanUsesOneSecondDelayAndBatchLimitTwo() throws Exception {
        when(jobService.pendingJobs(2)).thenReturn(List.of());

        worker.scan();

        Scheduled scheduled = InvestmentDataJobWorker.class.getMethod("scan").getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${investment.history-job.scan-delay-ms:1000}");
        verify(jobService).pendingJobs(2);
    }

    @Test
    void unclaimedJobIsNotExecuted() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(false);

        worker.scan();

        verifyNoInteractions(analysisService);
        verify(jobService, never()).markSucceeded(any(), anyString(), anyInt(), any());
    }

    @Test
    void initialStockJobRefreshesReliableSnapshotAndSucceedsAtMinimumHistory() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithQuotes(20));

        worker.scan();

        ArgumentCaptor<String> claimToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> finishToken = ArgumentCaptor.forClass(String.class);
        verify(jobService).claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), claimToken.capture());
        verify(analysisService).refresh(7L, 11L);
        verify(analysisService, never()).retryData(any(), any());
        verify(jobService).markSucceeded(eq(91L), finishToken.capture(), eq(20), eq(NOW));
        assertThat(finishToken.getValue()).isEqualTo(claimToken.getValue());
        assertThat(UUID.fromString(claimToken.getValue()).toString()).isEqualTo(claimToken.getValue());
    }

    @Test
    void incompleteFullCoverageRemainsPartial() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(historyPreparationService.prepare(job)).thenReturn(
                new InvestmentHistoryPreparationService.PreparationResult(
                        5_900,
                        LocalDate.of(2001, 8, 27),
                        LocalDate.of(2005, 1, 4),
                        LocalDate.of(2026, 7, 21),
                        false,
                        "dataset-incomplete"));

        worker.scan();

        verify(jobService).markPartial(
                eq(91L), anyString(), eq(5_900), contains("完整"), eq(NOW));
        verify(jobService, never()).markSucceeded(any(), anyString(), anyInt(), any());
    }

    @Test
    void forcedMutualFundJobReloadsUpstreamData() {
        InvestmentDataJob job = job(true, 0, "QUEUED");
        job.setJobType("FUND_NAV_HISTORY");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.retryData(7L, 11L)).thenReturn(detailWithQuotes(20));
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithState("READY"));

        worker.scan();

        verify(analysisService, never()).retryData(any(), any());
        verify(analysisService).refresh(7L, 11L);
        verify(jobService).markSucceeded(eq(91L), anyString(), eq(20), eq(NOW));
    }

    @Test
    void forcedHistoryJobRetriesWhenNewAnalysisWasNotProduced() {
        InvestmentDataJob job = job(true, 0, "QUEUED");
        job.setJobType("FUND_NAV_HISTORY");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.retryData(7L, 11L)).thenReturn(detailWithQuotes(20));
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithState("STABLE_CACHE"));

        worker.scan();

        verify(jobService).markRetryWait(eq(91L), anyString(), eq(1),
                eq(NOW.plusSeconds(60)), contains("新分析"), eq(NOW));
        verify(jobService, never()).markSucceeded(any(), anyString(), anyInt(), any());
    }

    @Test
    void benchmarkJobPersistsSnapshot() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        job.setJobType("BENCHMARK_HISTORY");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(benchmarkPreparationService.prepare(job)).thenReturn(1_344);

        worker.scan();

        verify(benchmarkPreparationService).prepare(job);
        verifyNoInteractions(analysisService);
        verify(jobService).markSucceeded(eq(91L), anyString(), eq(1_344), eq(NOW));
    }

    @Test
    void historyWithSomeRecordsBelowMinimumFinishesPartialWithoutRetry() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(historyPreparationService.prepare(any())).thenReturn(prepared(7));

        worker.scan();

        verify(jobService).markPartial(eq(91L), anyString(), eq(7), contains("20"), eq(NOW));
        verify(jobService, never()).markRetryWait(any(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void firstFailureWaitsSixtySecondsAndTruncatesError() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenThrow(new IllegalStateException("x".repeat(1200)));

        worker.scan();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(jobService).markRetryWait(eq(91L), anyString(), eq(1),
                eq(NOW.plusSeconds(60)), error.capture(), eq(NOW));
        assertThat(error.getValue()).hasSize(1000);
    }

    @Test
    void secondFailureWaitsFiveMinutes() {
        InvestmentDataJob job = job(false, 1, "RETRY_WAIT");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenThrow(new IllegalStateException("temporary"));

        worker.scan();

        verify(jobService).markRetryWait(eq(91L), anyString(), eq(2),
                eq(NOW.plusSeconds(300)), eq("temporary"), eq(NOW));
    }

    @Test
    void thirdFailureBecomesFailed() {
        InvestmentDataJob job = job(false, 2, "RETRY_WAIT");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenThrow(new IllegalStateException("still unavailable"));

        worker.scan();

        verify(jobService).markFailed(eq(91L), anyString(), eq(3), eq("still unavailable"), eq(NOW));
        verify(jobService, never()).markRetryWait(any(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void expiredRunningJobCanBeClaimedAndRecovered() {
        InvestmentDataJob job = job(false, 1, "RUNNING");
        job.setLeaseUntil(NOW.minusSeconds(1));
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithQuotes(20));

        worker.scan();

        verify(analysisService).refresh(7L, 11L);
        verify(jobService).markSucceeded(eq(91L), anyString(), eq(20), eq(NOW));
    }

    @Test
    void zeroHistoryIsRetriedInsteadOfMarkedPartial() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(historyPreparationService.prepare(any())).thenReturn(prepared(0));

        worker.scan();

        verify(jobService).markRetryWait(eq(91L), anyString(), eq(1),
                eq(NOW.plusSeconds(60)), contains("没有历史数据"), eq(NOW));
        verify(jobService, never()).markPartial(any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void successUsesCompletionTimeAfterExternalCall() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            return detailWithQuotes(20);
        });

        worker.scan();

        verify(jobService).markSucceeded(eq(91L), anyString(), eq(20), eq(NOW.plusSeconds(30)));
    }

    @Test
    void partialUsesCompletionTimeAfterExternalCall() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(historyPreparationService.prepare(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            return prepared(7);
        });

        worker.scan();

        verify(jobService).markPartial(eq(91L), anyString(), eq(7), anyString(), eq(NOW.plusSeconds(30)));
    }

    @Test
    void retryDelayStartsAtFailureCompletionTime() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            throw new IllegalStateException("temporary");
        });

        worker.scan();

        verify(jobService).markRetryWait(eq(91L), anyString(), eq(1),
                eq(NOW.plusSeconds(90)), eq("temporary"), eq(NOW.plusSeconds(30)));
    }

    @Test
    void failedUsesFailureCompletionTime() {
        InvestmentDataJob job = job(false, 2, "RETRY_WAIT");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            throw new IllegalStateException("still unavailable");
        });

        worker.scan();

        verify(jobService).markFailed(eq(91L), anyString(), eq(3),
                eq("still unavailable"), eq(NOW.plusSeconds(30)));
    }

    @Test
    void claimedSnapshotOverridesStalePendingForceRefreshAndAttemptCount() {
        InvestmentDataJob pending = job(false, 2, "QUEUED");
        InvestmentDataJob claimed = job(true, 0, "RUNNING");
        when(jobService.pendingJobs(2)).thenReturn(List.of(pending));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(claimed);
        when(historyPreparationService.prepare(any()))
                .thenThrow(new IllegalStateException("forced refresh failed"));

        worker.scan();

        verify(historyPreparationService).prepare(claimed);
        verifyNoInteractions(analysisService);
        verify(jobService).markRetryWait(eq(91L), anyString(), eq(1),
                eq(NOW.plusSeconds(60)), eq("forced refresh failed"), eq(NOW));
        verify(jobService, never()).markFailed(any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void invalidatedLeaseTokenStopsBeforeExternalCall() {
        InvestmentDataJob pending = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(pending));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(null);

        worker.scan();

        verifyNoInteractions(analysisService);
        verify(jobService, never()).markSucceeded(any(), anyString(), anyInt(), any());
        verify(jobService, never()).markPartial(any(), anyString(), anyInt(), anyString(), any());
        verify(jobService, never()).markRetryWait(any(), anyString(), anyInt(), any(), anyString(), any());
        verify(jobService, never()).markFailed(any(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void detailReadsAvailableDataAndQueuesAnalysisWithoutRemoteCalls() {
        ReadOnlyFixture fixture = readOnlyFixture(5);
        Map<String, Object> queued = Map.of(
                "status", "QUEUED", "recordCount", 0, "attemptCount", 0);
        when(fixture.jobService().statusForAsset(7L, 11L))
                .thenReturn(queued);

        InvestmentAssetDetailResponse first = fixture.service().detail(7L, 11L);

        assertThat(first.getSourceStatus()).containsEntry("historyJob", queued);
        verifyNoInteractions(fixture.analysisClient());
        verify(fixture.jobService(), times(1))
                .ensureRecoveryQueued(7L, 11L, 21L, "STOCK");
    }

    @Test
    void missingSnapshotQueuesAnalysisEvenWhenEnoughHistoryExists() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        InvestmentAssetDetailResponse detail = fixture.service().detail(7L, 11L);

        assertThat(detail.getSourceStatus()).containsEntry("historyJob", Map.of());
        assertThat(detail.getPersonalizedAction()).isEmpty();
        verifyNoInteractions(fixture.analysisClient());
        verify(fixture.jobService()).ensureRecoveryQueued(7L, 11L, 21L, "STOCK", true);
    }

    @Test
    void blockedQualityNeverReturnsHistoricalAnalysisSnapshot() throws Exception {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        InvestmentAnalysisSnapshot snapshot = historicalSnapshot();
        when(fixture.snapshotMapper().selectOne(any())).thenReturn(snapshot);
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "blocked-dataset",
                "qualityRuleSetVersion", "quality-v1",
                "status", "BLOCKED",
                "decision", "BLOCK"
        ));
        when(fixture.dataQualityService().resolve(any(), any(), any(), anyBoolean()))
                .thenReturn(blockedEvaluation());
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        InvestmentAssetDetailResponse detail = fixture.service().detail(7L, 11L);

        assertThat(detail.getTechnicalAnalysis())
                .containsEntry("status", "BLOCKED")
                .doesNotContainKeys("score", "verdict", "outlook", "action");
        assertThat(detail.getSourceStatus())
                .containsEntry("dataState", "BLOCKED")
                .containsEntry("historicalCache", false);
        verify(fixture.jobService()).ensureRecoveryQueued(7L, 11L, 21L, "STOCK");
        verify(fixture.analysisClient(), never())
                .technicalAnalysis(any(), any(), anyString(), any());
    }

    @Test
    void qualityServiceFailureIsWaitingAndNeverMasqueradesAsDataBlock() throws Exception {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        InvestmentAnalysisSnapshot snapshot = historicalSnapshot();
        when(fixture.snapshotMapper().selectOne(any())).thenReturn(snapshot);
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "previous-dataset",
                "qualityRuleSetVersion", "quality-v1",
                "status", "PASS",
                "decision", "ALLOW"
        ));
        when(fixture.dataQualityService().resolve(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("provider timeout"));
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        InvestmentAssetDetailResponse detail = fixture.service().refresh(7L, 11L);

        assertThat(detail.getTechnicalAnalysis())
                .containsEntry("status", "UNAVAILABLE")
                .doesNotContainKeys("score", "verdict", "outlook", "action");
        assertThat(detail.getSourceStatus())
                .containsEntry("dataState", "WAITING")
                .containsEntry("qualityStatus", "UNAVAILABLE")
                .containsEntry("qualityDecision", "WAITING")
                .containsEntry("historicalCache", false);
        verify(fixture.jobService()).ensureRecoveryQueued(7L, 11L, 21L, "STOCK");
    }

    @Test
    void recoveredQualityRecomputesAndReplacesHistoricalSnapshot() throws Exception {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        InvestmentAnalysisSnapshot snapshot = historicalSnapshot();
        when(fixture.snapshotMapper().selectOne(any())).thenReturn(snapshot);
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(
                Map.of(
                        "datasetVersion", "blocked-dataset",
                        "qualityRuleSetVersion", "quality-v1",
                        "status", "BLOCKED",
                        "decision", "BLOCK"
                ),
                Map.of(
                        "datasetVersion", "dataset-v2",
                        "qualityRuleSetVersion", "quality-v1",
                        "status", "PASS",
                        "decision", "ALLOW"
                )
        );
        when(fixture.dataQualityService().resolve(any(), any(), any(), anyBoolean()))
                .thenReturn(blockedEvaluation(), allowEvaluation(20));
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        InvestmentAssetDetailResponse blocked = fixture.service().refresh(7L, 11L);
        InvestmentAssetDetailResponse recovered = fixture.service().refresh(7L, 11L);

        assertThat(blocked.getTechnicalAnalysis()).containsEntry("status", "BLOCKED");
        assertThat(recovered.getTechnicalAnalysis())
                .containsEntry("status", "READY")
                .containsEntry("score", 60);
        assertThat(recovered.getSourceStatus())
                .containsEntry("dataState", "READY")
                .containsEntry("historicalCache", false);
        assertThat(snapshot.getTechnicalJson()).contains("\"score\":60").doesNotContain("\"score\":88");
        verify(fixture.analysisClient(), times(1))
                .technicalAnalysis(any(), any(), anyString(), any());
        verify(fixture.snapshotMapper(), atLeastOnce()).updateById(snapshot);
    }

    @Test
    void indexFundAnalysisPassesTheExistingCachedBenchmarkThroughTheMainAnalysisRequest() {
        ReadOnlyFixture fixture = readOnlyFixture(30, "MUTUAL_FUND");
        List<Map<String, Object>> benchmarkRecords = IntStream.range(0, 30)
                .mapToObj(index -> Map.<String, Object>of(
                        "data_date", LocalDate.of(2026, 6, 1).plusDays(index).toString(),
                        "close", 100 + index
                ))
                .toList();
        BenchmarkProfile profile = new BenchmarkProfile();
        profile.setProductCode("010736");
        profile.setModelFamily("INDEX_FUND");
        when(fixture.benchmarkProfileService().configuration(
                eq("MUTUAL_FUND"), eq("010736"), any(LocalDate.class)
        )).thenReturn(profile);
        when(fixture.benchmarkProfileService().resolveCached(
                eq("MUTUAL_FUND"),
                eq("010736"),
                any(LocalDate.class),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                true,
                "CSI300",
                "INDEX_FUND",
                "a".repeat(64),
                benchmarkRecords,
                null,
                null
        ));
        when(fixture.analysisClient().fundAnalysis(
                any(), eq("INDEX_FUND"), any(), eq("SHORT"), any()
        )).thenReturn(Map.of(
                "status", "READY",
                "strategyVersion", "technical-strategy-v4",
                "adviceStatus", "UNAVAILABLE",
                "reasonCode", "CATEGORY_STRATEGY_UNVALIDATED",
                "series", List.of(),
                "horizons", Map.of("SHORT", Map.of("status", "READY"))
        ));
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        fixture.service().refresh(7L, 11L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> benchmarkCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fixture.analysisClient()).fundAnalysis(
                any(), eq("INDEX_FUND"), any(), eq("SHORT"), benchmarkCaptor.capture()
        );
        assertThat(benchmarkCaptor.getValue())
                .containsEntry("status", "READY")
                .containsEntry("code", "CSI300")
                .containsEntry("sourceVersion", "a".repeat(64))
                .containsEntry("records", benchmarkRecords);
    }

    @Test
    void navWithoutTotalReturnIndexCannotProduceFundReturns() {
        ReadOnlyFixture fixture = readOnlyFixture(30, "MUTUAL_FUND");
        ProductDailyQuote navOnly = new ProductDailyQuote();
        navOnly.setProductId(21L);
        navOnly.setTradeDate(LocalDate.of(2026, 7, 1));
        navOnly.setAdjustType("NONE");
        navOnly.setClosePrice(java.math.BigDecimal.TEN);
        when(fixture.quoteMapper().selectList(any())).thenReturn(List.of(navOnly));

        InvestmentAssetDetailResponse detail = fixture.service().refresh(7L, 11L);

        assertThat(detail.getSourceStatus())
                .containsEntry("dataState", "BLOCKED")
                .containsEntry("analysisStatus", "BLOCKED")
                .containsEntry("quoteStatus", "INSUFFICIENT");
        assertThat(detail.getTechnicalAnalysis())
                .containsEntry("status", "BLOCKED");
        verify(fixture.analysisClient(), never()).fundAnalysis(any(), anyString(), any(), anyString(), any());
    }

    private static InvestmentHistoryPreparationService.PreparationResult prepared(int count) {
        return new InvestmentHistoryPreparationService.PreparationResult(count,
                LocalDate.of(2001, 8, 27), LocalDate.of(2001, 8, 27),
                LocalDate.of(2026, 7, 21), true, "dataset-test");
    }

    private static InvestmentDataJob job(boolean forceRefresh, int attempts, String status) {
        InvestmentDataJob job = new InvestmentDataJob();
        job.setId(91L);
        job.setUserId(7L);
        job.setAssetId(11L);
        job.setProductId(21L);
        job.setJobType("STOCK_HISTORY");
        job.setStatus(status);
        job.setForceRefresh(forceRefresh);
        job.setAttemptCount(attempts);
        return job;
    }

    @Test
    void freshCacheReturnsStoredDetailWithoutAnalysisOrQueue() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var response = fixture.service().detail(7L, 11L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixture.detailCache()).put(eq(7L), eq(11L), key.capture(), any());
        when(fixture.detailCache().get(7L, 11L)).thenReturn(new InvestmentDetailCacheService.Entry(
                7L, 11L, Instant.now(), Instant.now().plusSeconds(30), key.getValue(), response));
        clearInvocations(fixture.jobService(), fixture.analysisClient());

        assertThat(fixture.service().detail(7L, 11L).getSourceStatus()).containsEntry("cacheStatus", "FRESH");
        verifyNoInteractions(fixture.analysisClient());
        verify(fixture.jobService(), never()).ensureRecoveryQueued(any(), any(), any(), anyString());
    }

    @Test
    void staleCacheReturnsStoredDetailWithoutQueuingHistoryReload() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var response = fixture.service().detail(7L, 11L);
        var readyStatus = new LinkedHashMap<>(response.getSourceStatus());
        readyStatus.put("dataState", "READY");
        response.setSourceStatus(readyStatus);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixture.detailCache()).put(eq(7L), eq(11L), key.capture(), any());
        when(fixture.detailCache().get(7L, 11L)).thenReturn(new InvestmentDetailCacheService.Entry(
                7L, 11L, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), key.getValue(), response));
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of("status", "QUEUED"));
        clearInvocations(fixture.jobService(), fixture.analysisClient());

        var result = fixture.service().detail(7L, 11L);
        assertThat(result.getSourceStatus()).containsEntry("cacheStatus", "STALE").containsEntry("backgroundRefresh", true);
        verify(fixture.jobService(), never()).ensureRecoveryQueued(any(), any(), any(), anyString());
        verifyNoInteractions(fixture.analysisClient());
    }

    @Test
    void refreshWritesLatestDetailAndSubsequentReadUsesSnapshotWithoutRemoteCalls() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var refreshed = fixture.service().refresh(7L, 11L);
        verify(fixture.detailCache()).evict(7L, 11L);
        verify(fixture.detailCache()).put(eq(7L), eq(11L), anyString(), same(refreshed));
        ArgumentCaptor<InvestmentAnalysisSnapshot> saved = ArgumentCaptor.forClass(InvestmentAnalysisSnapshot.class);
        verify(fixture.snapshotMapper()).insert(saved.capture());
        assertThat(saved.getValue().getAnalyzedAt().getNano()).isZero();
        when(fixture.snapshotMapper().selectOne(any())).thenReturn(saved.getValue());
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "dataset-v2", "qualityRuleSetVersion", "quality-v1", "decision", "ALLOW", "status", "PASS"));
        clearInvocations(fixture.analysisClient(), fixture.jobService());

        var result = fixture.service().detail(7L, 11L);
        assertThat(result.getSourceStatus()).containsEntry("dataState", "READY");
        assertThat(result.getTechnicalAnalysis()).containsEntry("score", 60);
        verifyNoInteractions(fixture.analysisClient());
        verify(fixture.jobService(), never()).ensureRecoveryQueued(any(), any(), any(), anyString());
    }

    @Test
    void blockedQualityRowsAreNotPublishedToTheQuoteTable() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        InvestmentDataQualityService.Evaluation blocked = blockedEvaluation();
        var rows = List.<Map<String, Object>>of(Map.of("data_date", "2026-07-21", "close", "1"));
        when(fixture.dataQualityService().resolve(any(), any(), any(), anyBoolean()))
                .thenReturn(new InvestmentDataQualityService.Evaluation(
                        blocked.snapshot(), blocked.response(), rows, List.of()));

        fixture.service().refresh(7L, 11L);

        verifyNoInteractions(fixture.syncWorker());
    }

    @Test
    void deterministicQualityBlockDoesNotRetryTheSameHistory() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(historyPreparationService.prepare(job)).thenThrow(
                new InvestmentHistoryPreparationService.QualityBlockedException());

        worker.scan();

        verify(jobService).markFailed(eq(91L), anyString(), eq(1),
                contains("完整性校验未通过"), eq(NOW));
        verify(jobService, never()).markRetryWait(any(), anyString(), anyInt(), any(), anyString(), any());
    }

    @Test
    void fullHistoryQualitySnapshotDoesNotInvalidateReadyAnalysisForSameQuotes() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        fixture.service().refresh(7L, 11L);
        ArgumentCaptor<InvestmentAnalysisSnapshot> saved = ArgumentCaptor.forClass(InvestmentAnalysisSnapshot.class);
        verify(fixture.snapshotMapper()).insert(saved.capture());
        when(fixture.snapshotMapper().selectOne(any())).thenReturn(saved.getValue());
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "full-history-dataset",
                "qualityRuleSetVersion", saved.getValue().getQualityRuleSetVersion(),
                "decision", "ALLOW", "status", "PASS"));
        clearInvocations(fixture.analysisClient(), fixture.jobService());

        var detail = fixture.service().detail(7L, 11L);

        assertThat(detail.getSourceStatus()).containsEntry("dataState", "READY");
        assertThat(detail.getSourceStatus().get("analyzedAt")).isNotNull();
        verifyNoInteractions(fixture.analysisClient());
        verify(fixture.jobService(), never()).ensureRecoveryQueued(any(), any(), any(), anyString());
    }

    @Test
    void dataQualityRefreshEvictsAndQueuesWithoutAnalysis() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        fixture.service().queueDataRefresh(7L, 11L);
        verify(fixture.detailCache()).evict(7L, 11L);
        verify(fixture.jobService()).ensureQueued(7L, 11L, 21L, "STOCK", true);
        verifyNoInteractions(fixture.analysisClient());
    }

    @Test
    void preferenceChangesEvictAndReadWithoutAnalysis() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        fixture.service().updatePreference(7L, 11L, null);
        fixture.service().clearPreference(7L, 11L);
        verify(fixture.detailCache(), times(2)).evict(7L, 11L);
        verifyNoInteractions(fixture.analysisClient());
    }

    @Test
    void redisConnectionFailureStillReturnsDatabaseDetail() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        var actualCache = new InvestmentDetailCacheService(redis, new ObjectMapper().findAndRegisterModules(),
                new com.smartfinance.agent.investment.config.InvestmentDetailCacheProperties());
        when(fixture.detailCache().get(7L, 11L)).thenAnswer(call -> actualCache.get(7L, 11L));
        var response = fixture.service().detail(7L, 11L);
        assertThat(response.getAsset().getId()).isEqualTo(11L);
        assertThat(response.getSourceStatus()).containsEntry("cacheStatus", "MISS");
        verifyNoInteractions(fixture.analysisClient());
    }

    @Test
    void unauthorizedOrDeletedAssetCannotReadRedis() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        when(fixture.assetService().get(8L, 11L)).thenThrow(new IllegalArgumentException("资产不存在"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service().detail(8L, 11L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fixture.detailCache());
    }

    @Test
    void changedQualityGateInvalidatesEvenFreshCachedConclusions() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var response = fixture.service().detail(7L, 11L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixture.detailCache()).put(eq(7L), eq(11L), key.capture(), any());
        when(fixture.detailCache().get(7L, 11L)).thenReturn(new InvestmentDetailCacheService.Entry(
                7L, 11L, Instant.now(), Instant.now().plusSeconds(30), key.getValue(), response));
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of("decision", "BLOCK", "datasetVersion", "blocked"));
        var result = fixture.service().detail(7L, 11L);
        assertThat(result.getSourceStatus()).containsEntry("cacheStatus", "MISS").containsEntry("dataState", "BLOCKED");
        verifyNoInteractions(fixture.analysisClient());
    }

    @Test
    void concurrentQualityBlockCannotStampOldReadyResponseWithNewContext() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        when(fixture.dataQualityService().latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "blocked-dataset", "decision", "BLOCK", "status", "BLOCKED"));
        var response = fixture.service().refresh(7L, 11L);
        assertThat(response.getSourceStatus()).containsEntry("dataState", "READY");
        verify(fixture.detailCache(), never()).put(any(), any(), anyString(), any());
        assertThat(fixture.service().detail(7L, 11L).getSourceStatus()).containsEntry("dataState", "BLOCKED");
    }

    @Test
    void concurrentOtherHoldingChangePreventsStaleFinancialWarningsFromBeingCached() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var otherAsset = new com.smartfinance.agent.investment.entity.InvestmentAsset();
        otherAsset.setId(12L);
        otherAsset.setQuantity(java.math.BigDecimal.TEN);
        when(fixture.assetMapper().selectList(any())).thenReturn(List.of(), List.of(otherAsset));
        fixture.service().refresh(7L, 11L);
        verify(fixture.detailCache(), never()).put(any(), any(), anyString(), any());
    }

    @Test
    void lateCacheFillAfterAnotherHoldingMutationIsRejectedOnNextRead() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        var response = fixture.service().detail(7L, 11L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fixture.detailCache()).put(eq(7L), eq(11L), key.capture(), any());
        when(fixture.detailCache().get(7L, 11L)).thenReturn(new InvestmentDetailCacheService.Entry(
                7L, 11L, Instant.now(), Instant.now().plusSeconds(30), key.getValue(), response));
        var otherAsset = new com.smartfinance.agent.investment.entity.InvestmentAsset();
        otherAsset.setId(12L);
        otherAsset.setQuantity(java.math.BigDecimal.TEN);
        when(fixture.assetMapper().selectList(any())).thenReturn(List.of(otherAsset));
        assertThat(fixture.service().detail(7L, 11L).getSourceStatus()).containsEntry("cacheStatus", "MISS");
    }

    private static InvestmentAssetDetailResponse detailWithQuotes(int count) {
        InvestmentAssetDetailResponse detail = new InvestmentAssetDetailResponse();
        detail.setQuoteSeries(Collections.nCopies(count, Map.of("close", 1)));
        detail.setSourceStatus(Map.of("dataState", "READY", "historicalCache", false));
        return detail;
    }

    private static InvestmentAssetDetailResponse detailWithState(String dataState) {
        InvestmentAssetDetailResponse detail = new InvestmentAssetDetailResponse();
        detail.setSourceStatus(Map.of("dataState", dataState, "historicalCache", false));
        return detail;
    }

    private static ReadOnlyFixture readOnlyFixture(int quoteCount) {
        return readOnlyFixture(quoteCount, "STOCK");
    }

    private static ReadOnlyFixture readOnlyFixture(int quoteCount, String productType) {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        InvestmentHorizonService horizonService = mock(InvestmentHorizonService.class);
        InvestmentHorizonProperties horizonProperties = new InvestmentHorizonProperties();
        horizonProperties.setMinimumHistoryTradingDays(20);
        horizonProperties.setMaxHistoryTradingDays(2500);
        horizonProperties.setHistoryMultiplier(3);
        horizonProperties.setInteractiveHistoryMultiplier(1);
        horizonProperties.setIndicatorWarmupTradingDays(250);
        horizonProperties.setCalendarDaysPerYear(365);
        horizonProperties.setTradingDaysPerYear(240);
        horizonProperties.setCalendarBufferDays(30);
        horizonProperties.setAnalysisRuleVersion("rule-test");
        InvestmentRuntimeProperties runtimeProperties = new InvestmentRuntimeProperties();
        runtimeProperties.getMarket().setZone(ZoneId.of("Asia/Shanghai"));
        runtimeProperties.getDataQuality().setStockAdjustType("QFQ");
        runtimeProperties.getDataQuality().setFundAdjustType("NONE");
        runtimeProperties.getAnalysis().setStrategyVersion("technical-strategy-v4");
        InvestmentAnalysisSnapshotMapper snapshotMapper = mock(InvestmentAnalysisSnapshotMapper.class);
        AnalysisServiceClient analysisClient = mock(AnalysisServiceClient.class);
        InvestmentDataQualityService dataQualityService = mock(InvestmentDataQualityService.class);
        InvestmentSyncWorker syncWorker = mock(InvestmentSyncWorker.class);
        UnifiedMarketDataIngestionService ingestion = mock(UnifiedMarketDataIngestionService.class);
        WealthService wealthService = mock(WealthService.class);
        FinancialProfileMapper financialProfileMapper = mock(FinancialProfileMapper.class);
        InvestmentDataJobService jobService = mock(InvestmentDataJobService.class);
        QuantBenchmarkProfileService benchmarkProfileService = mock(QuantBenchmarkProfileService.class);
        InvestmentDetailCacheService detailCache = mock(InvestmentDetailCacheService.class);
        var assetMapper = mock(com.smartfinance.agent.investment.mapper.InvestmentAssetMapper.class);

        InvestmentAssetView asset = new InvestmentAssetView();
        asset.setId(11L);
        asset.setProductId(21L);
        asset.setProductType(productType);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType(productType);
        product.setCode("MUTUAL_FUND".equals(productType) ? "010736" : "600000");
        product.setName("MUTUAL_FUND".equals(productType) ? "测试指数基金" : "测试股票");
        if ("MUTUAL_FUND".equals(productType)) {
            product.setFundCategory("INDEX_FUND");
            product.setClassificationSource("OFFICIAL_PROFILE");
            product.setClassificationVersion("fund-classification-v1");
        }
        ResolvedHorizonProfile profile = new ResolvedHorizonProfile(
                "template:test", "test",
                List.of(new HorizonSetting("SHORT", "短线", 1, 5, 20, true, "TEMPLATE")),
                List.of());
        List<ProductDailyQuote> quotes = IntStream.range(0, quoteCount).mapToObj(index -> {
            ProductDailyQuote quote = new ProductDailyQuote();
            quote.setProductId(21L);
            quote.setTradeDate(java.time.LocalDate.of(2026, 6, 1).plusDays(index));
            quote.setAdjustType("MUTUAL_FUND".equals(productType) ? "NONE" : "QFQ");
            quote.setClosePrice(java.math.BigDecimal.TEN);
            if ("MUTUAL_FUND".equals(productType)) quote.setTotalReturnIndex(java.math.BigDecimal.TEN);
            return quote;
        }).toList();
        when(assetService.get(7L, 11L)).thenReturn(asset);
        when(productMapper.selectById(21L)).thenReturn(product);
        when(horizonService.resolve(7L, 11L)).thenReturn(profile);
        when(quoteMapper.selectList(any())).thenReturn(quotes);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(snapshotMapper.insert(any())).thenAnswer(call -> {
            InvestmentAnalysisSnapshot saved = call.getArgument(0);
            when(snapshotMapper.selectOne(any())).thenReturn(saved);
            return 1;
        });
        when(dataQualityService.latestStatus(any())).thenReturn(Map.of(
                "datasetVersion", "dataset-v2", "qualityRuleSetVersion", "quality-v1", "decision", "ALLOW", "status", "PASS"));
        when(wealthService.overview(7L)).thenReturn(new WealthOverviewResponse());
        when(dataQualityService.resolve(eq(product), any(), any(), anyBoolean()))
                .thenReturn(allowEvaluation(quoteCount));
        when(ingestion.ingest(eq(product), any(), any(), anyString(), any(), anyBoolean()))
                .thenAnswer(call -> {
                    InvestmentDataQualityService.Evaluation evaluation = dataQualityService.resolve(
                            product, call.getArgument(1), call.getArgument(2), call.getArgument(5));
                    if (evaluation.blocked())
                        throw new UnifiedMarketDataIngestionService.QualityBlockedException(evaluation);
                    return new UnifiedMarketDataIngestionService.Result(quoteCount, null, null,
                            true, evaluation.datasetVersion(), "COMPLETE", evaluation);
                });
        List<Map<String, Object>> records = allowEvaluation(quoteCount).records();
        Map<String, Object> technical = new LinkedHashMap<>();
        technical.put("status", "READY");
        technical.put("strategyVersion", "technical-strategy-v4");
        technical.put("score", 60);
        technical.put("series", records);
        technical.put("horizons", Map.of("SHORT", Map.of("status", "READY")));
        technical.put("outlook", Map.of("direction", "BULLISH", "confidence", "MEDIUM"));
        when(analysisClient.technicalAnalysis(any(), any(), anyString(), any()))
                .thenReturn(technical);
        when(analysisClient.fundamentalAnalysis(anyString(), anyString()))
                .thenReturn(Map.of(
                        "status", "READY",
                        "verdict", "FAIR",
                        "strategyVersion", "technical-strategy-v4"
                ));
        InvestmentFinancialWarningEngine warningEngine = mock(InvestmentFinancialWarningEngine.class);
        when(warningEngine.evaluate(any())).thenReturn(List.of());

        InvestmentAnalysisServiceImpl service = new InvestmentAnalysisServiceImpl(
                assetService, productMapper, quoteMapper, horizonService, horizonProperties,
                runtimeProperties, snapshotMapper, analysisClient, dataQualityService, ingestion,
                wealthService, financialProfileMapper,
                new ObjectMapper().findAndRegisterModules(), jobService,
                warningEngine,
                benchmarkProfileService, detailCache, assetMapper);
        return new ReadOnlyFixture(
                service,
                jobService,
                analysisClient,
                syncWorker,
                snapshotMapper,
                dataQualityService,
                benchmarkProfileService, detailCache, assetService, assetMapper, quoteMapper
        );
    }

    private static InvestmentAnalysisSnapshot historicalSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setId(31L);
        snapshot.setUserId(7L);
        snapshot.setAssetId(11L);
        snapshot.setRuleVersion("rule-test");
        snapshot.setAnalysisStatus("READY");
        snapshot.setTechnicalJson(objectMapper.writeValueAsString(Map.of(
                "status", "READY",
                "score", 88,
                "verdict", "FAVORABLE",
                "outlook", Map.of("direction", "BULLISH")
        )));
        snapshot.setFundamentalJson(objectMapper.writeValueAsString(Map.of(
                "status", "READY",
                "verdict", "ATTRACTIVE"
        )));
        snapshot.setFundJson("{}");
        snapshot.setSourceStatusJson("{}");
        return snapshot;
    }

    private static InvestmentDataQualityService.Evaluation blockedEvaluation() {
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDatasetVersion("blocked-dataset");
        snapshot.setQualityStatus("BLOCKED");
        snapshot.setQualityRuleSetVersion("quality-v1");
        snapshot.setDecision("BLOCK");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", List.of());
        response.put("manifest", Map.of("provider", "TEST", "adapterVersion", "v1"));
        response.put("qualityReport", Map.of(
                "status", "BLOCKED",
                "decision", "BLOCK",
                "qualityRuleSetVersion", "quality-v1",
                "issues", List.of(Map.of(
                        "ruleCode", "COMMON_POSITIVE_VALUES",
                        "severity", "CRITICAL",
                        "outcome", "FAIL",
                        "message", "净值必须为正数"
                ))
        ));
        response.put("secondaryDatasetVersions", List.of());
        return new InvestmentDataQualityService.Evaluation(
                snapshot,
                response,
                List.of(),
                List.of()
        );
    }

    private static InvestmentDataQualityService.Evaluation allowEvaluation(int recordCount) {
        List<Map<String, Object>> records = IntStream.range(0, recordCount).mapToObj(index -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("data_date", LocalDate.of(2026, 6, 1).plusDays(index).toString());
            row.put("open", "1");
            row.put("high", "1");
            row.put("low", "1");
            row.put("close", "1");
            row.put("volume", "1");
            return row;
        }).toList();
        InvestmentDataQualitySnapshot snapshot = new InvestmentDataQualitySnapshot();
        snapshot.setDatasetVersion("dataset-v2");
        snapshot.setQualityStatus("PASS");
        snapshot.setQualityRuleSetVersion("quality-v1");
        snapshot.setDecision("ALLOW");
        snapshot.setRequestedStartDate(LocalDate.of(2026, 6, 1));
        snapshot.setRequestedEndDate(LocalDate.of(2026, 6, 1).plusDays(recordCount - 1L));
        snapshot.setSampleStartDate(snapshot.getRequestedStartDate());
        snapshot.setSampleEndDate(snapshot.getRequestedEndDate());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", records);
        response.put("manifest", Map.of("provider", "TEST", "adapterVersion", "v1"));
        response.put("qualityReport", Map.of(
                "status", "PASS",
                "decision", "ALLOW",
                "qualityRuleSetVersion", "quality-v1",
                "issues", List.of()
        ));
        response.put("secondaryDatasetVersions", List.of());
        return new InvestmentDataQualityService.Evaluation(snapshot, response, records, List.of());
    }

    private record ReadOnlyFixture(InvestmentAnalysisServiceImpl service,
                                   InvestmentDataJobService jobService,
                                   AnalysisServiceClient analysisClient,
                                   InvestmentSyncWorker syncWorker,
                                   InvestmentAnalysisSnapshotMapper snapshotMapper,
                                   InvestmentDataQualityService dataQualityService,
                                   QuantBenchmarkProfileService benchmarkProfileService,
                                   InvestmentDetailCacheService detailCache, InvestmentAssetService assetService,
                                   com.smartfinance.agent.investment.mapper.InvestmentAssetMapper assetMapper,
                                   ProductDailyQuoteMapper quoteMapper) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
