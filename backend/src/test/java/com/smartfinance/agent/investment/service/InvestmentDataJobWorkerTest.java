package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAnalysisSnapshotMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.quant.QuantAutomationService;
import com.smartfinance.agent.investment.quant.QuantBenchmarkPreparationService;
import com.smartfinance.agent.investment.quant.QuantResearchUniversePreparationService;
import com.smartfinance.agent.investment.quant.QuantModelMonitorMapper;
import com.smartfinance.agent.investment.quant.QuantStrategyVersionMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestmentDataJobWorkerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 10, 0);

    private InvestmentDataJobService jobService;
    private InvestmentAnalysisService analysisService;
    private InvestmentDataJobWorker worker;
    private QuantAutomationService quantAutomationService;
    private QuantBenchmarkPreparationService benchmarkPreparationService;
    private QuantResearchUniversePreparationService researchUniversePreparationService;
    private InvestmentHistoryPreparationService historyPreparationService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jobService = mock(InvestmentDataJobService.class);
        analysisService = mock(InvestmentAnalysisService.class);
        quantAutomationService = mock(QuantAutomationService.class);
        benchmarkPreparationService = mock(QuantBenchmarkPreparationService.class);
        researchUniversePreparationService = mock(QuantResearchUniversePreparationService.class);
        historyPreparationService = mock(InvestmentHistoryPreparationService.class);
        when(jobService.markSucceeded(any(), anyString(), anyInt(), any()))
                .thenReturn(true);
        when(jobService.recordCoverage(any(), anyString(), any()))
                .thenReturn(true);
        InvestmentHorizonProperties horizonProperties = new InvestmentHorizonProperties();
        horizonProperties.setMinimumHistoryTradingDays(20);
        clock = new MutableClock(Instant.parse("2026-07-22T02:00:00Z"), SHANGHAI);
        worker = new InvestmentDataJobWorker(
                jobService,
                analysisService,
                horizonProperties,
                clock,
                quantAutomationService,
                benchmarkPreparationService,
                researchUniversePreparationService,
                historyPreparationService
        );
        when(historyPreparationService.prepare(any())).thenAnswer(invocation -> {
            InvestmentDataJob job = invocation.getArgument(0);
            InvestmentAssetDetailResponse detail = Boolean.TRUE.equals(job.getForceRefresh())
                    ? analysisService.retryData(job.getUserId(), job.getAssetId())
                    : analysisService.refresh(job.getUserId(), job.getAssetId());
            int count = detail == null || detail.getQuoteSeries() == null
                    ? 0 : detail.getQuoteSeries().size();
            return new InvestmentHistoryPreparationService.PreparationResult(
                    count,
                    LocalDate.of(2001, 8, 27),
                    LocalDate.of(2001, 8, 27),
                    LocalDate.of(2026, 7, 21),
                    true,
                    "dataset-test");
        });
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
        verify(quantAutomationService).onDataReady(7L, 11L);
        assertThat(finishToken.getValue()).isEqualTo(claimToken.getValue());
        assertThat(UUID.fromString(claimToken.getValue()).toString()).isEqualTo(claimToken.getValue());
    }

    @Test
    void incompleteFullCoverageNeverStartsQuantTraining() {
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
        verify(quantAutomationService, never()).onDataReady(any(), any());
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

        worker.scan();

        verify(analysisService).retryData(7L, 11L);
        verify(analysisService, never()).refresh(any(), any());
        verify(jobService).markSucceeded(eq(91L), anyString(), eq(20), eq(NOW));
        verify(quantAutomationService).onDataReady(7L, 11L);
    }

    @Test
    void benchmarkJobPersistsSnapshotThenResumesQuantAutomation() {
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
        verify(quantAutomationService).onDataReady(7L, 11L);
    }

    @Test
    void lostLeaseDoesNotTriggerQuantAutomation() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        job.setJobType("BENCHMARK_HISTORY");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(eq(91L), eq(NOW), eq(NOW.plusSeconds(120)), anyString()))
                .thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(benchmarkPreparationService.prepare(job)).thenReturn(1_344);
        when(jobService.markSucceeded(eq(91L), anyString(), eq(1_344), eq(NOW)))
                .thenReturn(false);

        worker.scan();

        verify(quantAutomationService, never()).onDataReady(any(), any());
    }

    @Test
    void historyWithSomeRecordsBelowMinimumFinishesPartialWithoutRetry() {
        InvestmentDataJob job = job(false, 0, "QUEUED");
        when(jobService.pendingJobs(2)).thenReturn(List.of(job));
        when(jobService.claim(any(), any(), any(), anyString())).thenReturn(true);
        when(jobService.claimedSnapshot(eq(91L), anyString())).thenReturn(job);
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithQuotes(7));

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
        when(analysisService.refresh(7L, 11L)).thenReturn(detailWithQuotes(0));

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
        when(analysisService.refresh(7L, 11L)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            return detailWithQuotes(7);
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
        when(analysisService.retryData(7L, 11L))
                .thenThrow(new IllegalStateException("forced refresh failed"));

        worker.scan();

        verify(analysisService).retryData(7L, 11L);
        verify(analysisService, never()).refresh(any(), any());
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
    void readOnlyDetailQueuesLegacyAssetOnceAndExposesHistoryJobWithoutExternalCalls() {
        ReadOnlyFixture fixture = readOnlyFixture(5);
        Map<String, Object> queued = Map.of(
                "status", "QUEUED", "recordCount", 0, "attemptCount", 0);
        when(fixture.jobService().statusForAsset(7L, 11L))
                .thenReturn(Map.of(), queued, queued);

        InvestmentAssetDetailResponse first = fixture.service().detail(7L, 11L);
        InvestmentAssetDetailResponse second = fixture.service().detail(7L, 11L);

        assertThat(first.getSourceStatus()).containsEntry("historyJob", queued);
        assertThat(second.getSourceStatus()).containsEntry("historyJob", queued);
        verify(fixture.jobService(), times(1))
                .ensureQueued(7L, 11L, 21L, "STOCK", false);
        verifyNoInteractions(fixture.analysisClient(), fixture.syncWorker());
    }

    @Test
    void readOnlyDetailDoesNotQueueLegacyAssetWithEnoughHistory() {
        ReadOnlyFixture fixture = readOnlyFixture(20);
        when(fixture.jobService().statusForAsset(7L, 11L)).thenReturn(Map.of());

        InvestmentAssetDetailResponse detail = fixture.service().detail(7L, 11L);

        assertThat(detail.getSourceStatus()).containsEntry("historyJob", Map.of());
        verify(fixture.jobService(), never())
                .ensureQueued(any(), any(), any(), anyString(), anyBoolean());
        verifyNoInteractions(fixture.analysisClient(), fixture.syncWorker());
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

    private static InvestmentAssetDetailResponse detailWithQuotes(int count) {
        InvestmentAssetDetailResponse detail = new InvestmentAssetDetailResponse();
        detail.setQuoteSeries(Collections.nCopies(count, Map.of("close", 1)));
        return detail;
    }

    private static ReadOnlyFixture readOnlyFixture(int quoteCount) {
        InvestmentAssetService assetService = mock(InvestmentAssetService.class);
        InvestmentProductMapper productMapper = mock(InvestmentProductMapper.class);
        ProductDailyQuoteMapper quoteMapper = mock(ProductDailyQuoteMapper.class);
        InvestmentHorizonService horizonService = mock(InvestmentHorizonService.class);
        InvestmentHorizonProperties horizonProperties = new InvestmentHorizonProperties();
        horizonProperties.setMinimumHistoryTradingDays(20);
        horizonProperties.setAnalysisRuleVersion("rule-test");
        InvestmentRuntimeProperties runtimeProperties = new InvestmentRuntimeProperties();
        runtimeProperties.getDataQuality().setStockAdjustType("QFQ");
        runtimeProperties.getDataQuality().setFundAdjustType("NONE");
        runtimeProperties.getAi().setCooldownMinutes(30);
        InvestmentAnalysisSnapshotMapper snapshotMapper = mock(InvestmentAnalysisSnapshotMapper.class);
        AnalysisServiceClient analysisClient = mock(AnalysisServiceClient.class);
        InvestmentDataQualityService dataQualityService = mock(InvestmentDataQualityService.class);
        InvestmentSyncWorker syncWorker = mock(InvestmentSyncWorker.class);
        WealthService wealthService = mock(WealthService.class);
        FinancialProfileMapper financialProfileMapper = mock(FinancialProfileMapper.class);
        InvestmentAiExplanationService aiExplanationService = mock(InvestmentAiExplanationService.class);
        PersonalizedActionCalculator actionCalculator = mock(PersonalizedActionCalculator.class);
        InvestmentDataJobService jobService = mock(InvestmentDataJobService.class);
        QuantStrategyVersionMapper strategyMapper = mock(QuantStrategyVersionMapper.class);
        QuantModelMonitorMapper monitorMapper = mock(QuantModelMonitorMapper.class);

        InvestmentAssetView asset = new InvestmentAssetView();
        asset.setId(11L);
        asset.setProductId(21L);
        asset.setProductType("STOCK");
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setName("测试股票");
        ResolvedHorizonProfile profile = new ResolvedHorizonProfile(
                "template:test", "test",
                List.of(new HorizonSetting("SHORT", "短线", 1, 5, 20, true, "TEMPLATE")),
                List.of());
        List<ProductDailyQuote> quotes = IntStream.range(0, quoteCount).mapToObj(index -> {
            ProductDailyQuote quote = new ProductDailyQuote();
            quote.setProductId(21L);
            quote.setTradeDate(java.time.LocalDate.of(2026, 6, 1).plusDays(index));
            quote.setAdjustType("QFQ");
            quote.setClosePrice(java.math.BigDecimal.TEN);
            return quote;
        }).toList();
        when(assetService.get(7L, 11L)).thenReturn(asset);
        when(productMapper.selectById(21L)).thenReturn(product);
        when(horizonService.resolve(7L, 11L)).thenReturn(profile);
        when(quoteMapper.selectList(any())).thenReturn(quotes);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(dataQualityService.latestStatus(product)).thenReturn(Map.of());
        when(wealthService.overview(7L)).thenReturn(new WealthOverviewResponse());

        InvestmentAnalysisServiceImpl service = new InvestmentAnalysisServiceImpl(
                assetService, productMapper, quoteMapper, horizonService, horizonProperties,
                runtimeProperties, snapshotMapper, analysisClient, dataQualityService, syncWorker,
                wealthService, financialProfileMapper, aiExplanationService, actionCalculator,
                new ObjectMapper(), jobService,
                new InvestmentFinancialWarningEngine(runtimeProperties),
                strategyMapper, monitorMapper);
        return new ReadOnlyFixture(service, jobService, analysisClient, syncWorker);
    }

    private record ReadOnlyFixture(InvestmentAnalysisServiceImpl service,
                                   InvestmentDataJobService jobService,
                                   AnalysisServiceClient analysisClient,
                                   InvestmentSyncWorker syncWorker) {
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
