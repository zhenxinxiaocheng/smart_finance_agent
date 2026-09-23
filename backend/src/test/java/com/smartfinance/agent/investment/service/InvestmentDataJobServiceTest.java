package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentDataJobServiceTest {

    private InvestmentDataJobMapper mapper;
    private InvestmentProductMapper productMapper;
    private InvestmentAssetMapper assetMapper;
    private ProductDailyQuoteMapper quoteMapper;
    private InvestmentDataJobService service;

    @BeforeEach
    void setUp() {
        mapper = mock(InvestmentDataJobMapper.class);
        when(mapper.requeueTerminal(any())).thenReturn(1);
        when(mapper.requeueTerminalIncremental(any())).thenReturn(1);
        productMapper = mock(InvestmentProductMapper.class);
        assetMapper = mock(InvestmentAssetMapper.class);
        quoteMapper = mock(ProductDailyQuoteMapper.class);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setHistoryCoverageComplete(true);
        when(productMapper.selectById(21L)).thenReturn(product);
        service = new InvestmentDataJobService(mapper, productMapper, assetMapper, quoteMapper);
    }

    @Test
    void claimAlwaysRequiresCallerGeneratedLeaseToken() {
        assertThat(Arrays.stream(InvestmentDataJobService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("claim")))
                .allMatch(method -> method.getParameterCount() == 4);
    }

    @Test
    void claimedSnapshotReturnsRunningJobOwnedByLeaseToken() {
        InvestmentDataJob claimed = job("RUNNING", "STOCK_HISTORY");
        claimed.setId(91L);
        claimed.setLeaseToken("worker-token");
        when(mapper.selectOne(any())).thenReturn(claimed);

        InvestmentDataJob result = service.claimedSnapshot(91L, "worker-token");

        assertThat(result).isSameAs(claimed);
        verify(mapper).selectOne(any());
    }

    @Test
    void firstQueueCreatesStockHistoryJob() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            InvestmentDataJob job = invocation.getArgument(0);
            job.setId(91L);
            return 1;
        });

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(job.getId()).isEqualTo(91L);
        assertThat(job.getUserId()).isEqualTo(7L);
        assertThat(job.getAssetId()).isEqualTo(11L);
        assertThat(job.getProductId()).isEqualTo(21L);
        assertThat(job.getJobType()).isEqualTo("STOCK_HISTORY");
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isFalse();
        verify(mapper).insert(job);
    }

    @Test
    void sameAssetAndTypeReusesExistingActiveJob() {
        InvestmentDataJob existing = job("RUNNING", "STOCK_HISTORY");
        existing.setId(91L);
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(job).isSameAs(existing);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void fundUsesFundNavHistoryJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "FUND", false);

        assertThat(job.getJobType()).isEqualTo("FUND_NAV_HISTORY");
    }

    @Test
    void mutualFundUsesFundNavHistoryJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "MUTUAL_FUND", false);

        assertThat(job.getJobType()).isEqualTo("FUND_NAV_HISTORY");
    }

    @Test
    void benchmarkPreparationUsesDedicatedReusableJobType() {
        when(mapper.insert(any())).thenReturn(1);

        InvestmentDataJob job = service.ensureBenchmarkQueued(7L, 11L, 21L);

        assertThat(job.getUserId()).isEqualTo(7L);
        assertThat(job.getAssetId()).isEqualTo(11L);
        assertThat(job.getProductId()).isEqualTo(21L);
        assertThat(job.getJobType()).isEqualTo("BENCHMARK_HISTORY");
        assertThat(job.getStatus()).isEqualTo("QUEUED");
        verify(mapper).insert(job);
    }

    @Test
    void unknownProductTypeIsRejected() {
        assertThatThrownBy(() -> service.ensureQueued(7L, 11L, 21L, "BOND", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("产品类型");
    }

    @Test
    void duplicateInsertRequeriesAndReturnsTheWinningJob() {
        InvestmentDataJob winner = job("QUEUED", "STOCK_HISTORY");
        winner.setId(92L);
        when(mapper.selectOne(any())).thenReturn(null, winner);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("unique key"));

        InvestmentDataJob result = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(result).isSameAs(winner);
        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void manualRefreshResetsTerminalJobForForcedReload() {
        InvestmentDataJob existing = job("FAILED", "STOCK_HISTORY");
        existing.setAttemptCount(3);
        existing.setRecordCount(18);
        existing.setErrorMessage("upstream unavailable");
        existing.setNextRetryAt(LocalDateTime.of(2026, 7, 22, 10, 30));
        existing.setLeaseUntil(LocalDateTime.of(2026, 7, 22, 10, 5));
        existing.setLeaseToken("old-worker");
        existing.setStartedAt(LocalDateTime.of(2026, 7, 22, 9, 55));
        existing.setFinishedAt(LocalDateTime.of(2026, 7, 22, 10, 0));
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", true);

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isTrue();
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getErrorMessage()).isNull();
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        verify(mapper).requeueTerminal(existing.getId());
    }

    @Test
    void forcedRefreshNeverResetsAnActiveRecoveryJob() {
        InvestmentDataJob existing = job("RUNNING", "STOCK_HISTORY");
        existing.setAttemptCount(1);
        existing.setLeaseToken("active-worker");
        existing.setLeaseUntil(LocalDateTime.of(2026, 7, 22, 10, 5));
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", true);

        assertThat(job.getStatus()).isEqualTo("RUNNING");
        assertThat(job.getLeaseToken()).isEqualTo("active-worker");
        assertThat(job.getAttemptCount()).isEqualTo(1);
        verify(mapper, never()).updateById(any());
    }

    @Test
    void automaticRecoveryDoesNotLoopAFailedJob() {
        InvestmentDataJob existing = job("FAILED", "STOCK_HISTORY");
        existing.setFinishedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
        existing.setAttemptCount(3);
        existing.setErrorMessage("quality remains blocked");
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK");

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getAttemptCount()).isEqualTo(3);
        verify(mapper, never()).requeueTerminal(any());
    }

    @Test
    void automaticRecoveryDoesNotLoopPartialJob() {
        InvestmentDataJob existing = job("PARTIAL", "STOCK_HISTORY");
        existing.setFinishedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK").getStatus())
                .isEqualTo("PARTIAL");
        verify(mapper, never()).requeueTerminal(any());
    }

    @Test
    void scannerDoesNotRequeueTerminalIncompleteJob() {
        InvestmentAsset asset = asset(7L, 11L, 21L);
        when(assetMapper.selectList(null)).thenReturn(List.of(asset));
        when(productMapper.selectById(21L)).thenReturn(product(21L, "STOCK", false));
        InvestmentDataJob existing = job("FAILED", "STOCK_HISTORY");
        existing.setFinishedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.requeueIncompleteHistoryJobs()).isZero();
        verify(mapper, never()).requeueTerminal(any());
    }

    @Test
    void automaticRecoveryRequeuesACompletedJobIncrementally() {
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        existing.setRecordCount(526);
        existing.setSampleEndDate(LocalDate.of(2026, 7, 21));
        when(quoteMapper.latestTradeDate(21L)).thenReturn(LocalDate.of(2026, 7, 22));
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK");

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isFalse();
        assertThat(job.getRecordCount()).isZero();
        verify(mapper).requeueTerminalIncremental(existing.getId());
    }

    @Test
    void completedJobIsNotRequeuedWithoutNewQuotes() {
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        existing.setSampleEndDate(LocalDate.of(2026, 7, 22));
        when(quoteMapper.latestTradeDate(21L)).thenReturn(LocalDate.of(2026, 7, 22));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK").getStatus())
                .isEqualTo("SUCCEEDED");
        verify(mapper, never()).requeueTerminalIncremental(any());
    }

    @Test
    void incompleteCoverageDoesNotResetCompletedJobWithoutManualForce() {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setHistoryCoverageComplete(false);
        when(productMapper.selectById(21L)).thenReturn(product);
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        existing.setId(91L);
        existing.setRecordCount(526);
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob job = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(job.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(job.getForceRefresh()).isFalse();
        assertThat(job.getAttemptCount()).isZero();
        verify(mapper, never()).requeueTerminal(any());
    }

    @Test
    void requeueIncompleteHistoryJobsCoversEveryIncompleteAsset() {
        InvestmentProduct stock = product(21L, "STOCK", false);
        InvestmentProduct fund = product(31L, "MUTUAL_FUND", false);
        InvestmentProduct complete = product(41L, "STOCK", true);
        when(productMapper.selectById(21L)).thenReturn(stock);
        when(productMapper.selectById(31L)).thenReturn(fund);
        when(productMapper.selectById(41L)).thenReturn(complete);
        InvestmentAsset stockAsset = asset(7L, 11L, 21L);
        InvestmentAsset fundAsset = asset(7L, 12L, 31L);
        InvestmentAsset completeAsset = asset(8L, 13L, 41L);
        when(assetMapper.selectList(null)).thenReturn(List.of(stockAsset, fundAsset, completeAsset));
        when(mapper.insert(any())).thenAnswer(invocation -> {
            InvestmentDataJob job = invocation.getArgument(0);
            job.setId(job.getProductId() + 100L);
            return 1;
        });

        int requeued = service.requeueIncompleteHistoryJobs();

        assertThat(requeued).isEqualTo(2);
        verify(mapper).insert(argThat(job -> "STOCK_HISTORY".equals(job.getJobType())
                && Boolean.TRUE.equals(job.getForceRefresh())));
        verify(mapper).insert(argThat(job -> "FUND_NAV_HISTORY".equals(job.getJobType())
                && Boolean.TRUE.equals(job.getForceRefresh())));
    }

    @Test
    void anotherUserCannotReadAssetJobStatus() {
        when(mapper.selectOne(any())).thenReturn(null);

        var status = service.statusForAsset(8L, 11L);

        assertThat(status).isEmpty();
    }

    @Test
    void statusIncludesAttemptCount() {
        InvestmentDataJob existing = job("RETRY_WAIT", "STOCK_HISTORY");
        existing.setAttemptCount(2);
        when(assetMapper.selectOne(any())).thenReturn(asset(7L, 11L, 21L));
        when(mapper.selectOne(any())).thenReturn(existing);

        var status = service.statusForAsset(7L, 11L);

        assertThat(status).containsEntry("attemptCount", 2);
    }

    @Test
    void statusExposesOnlySafeHistoryJobFields() {
        InvestmentDataJob existing = job("RUNNING", "STOCK_HISTORY");
        existing.setRecordCount(12);
        existing.setAttemptCount(1);
        existing.setErrorMessage("temporary");
        existing.setStartedAt(LocalDateTime.of(2026, 7, 22, 9, 55));
        existing.setFinishedAt(LocalDateTime.of(2026, 7, 22, 9, 56));
        existing.setUpdatedAt(LocalDateTime.of(2026, 7, 22, 10, 0));
        existing.setRequestedStartDate(LocalDate.of(2001, 8, 27));
        existing.setSampleStartDate(LocalDate.of(2001, 8, 27));
        existing.setSampleEndDate(LocalDate.of(2026, 7, 21));
        existing.setCoverageComplete(true);
        existing.setDatasetVersion("dataset-v1");
        when(assetMapper.selectOne(any())).thenReturn(asset(7L, 11L, 21L));
        when(mapper.selectOne(any())).thenReturn(existing);

        var status = service.statusForAsset(7L, 11L);

        assertThat(status).containsOnlyKeys(
                "status", "recordCount", "attemptCount", "errorMessage", "updatedAt",
                "requestedStartDate", "sampleStartDate", "sampleEndDate",
                "coverageComplete", "datasetVersion");
        assertThat(status).containsEntry("coverageComplete", true);
    }

    @Test
    void recordsActualCoverageOnlyForCurrentLeaseOwner() {
        when(mapper.updateCoverage(
                91L,
                "worker-token",
                LocalDate.of(2001, 8, 27),
                LocalDate.of(2001, 8, 27),
                LocalDate.of(2026, 7, 21),
                true,
                "dataset-v1")).thenReturn(1);

        boolean updated = service.recordCoverage(
                91L,
                "worker-token",
                new InvestmentHistoryPreparationService.PreparationResult(
                        5_987,
                        LocalDate.of(2001, 8, 27),
                        LocalDate.of(2001, 8, 27),
                        LocalDate.of(2026, 7, 21),
                        true,
                        "dataset-v1"));

        assertThat(updated).isTrue();
    }

    private static InvestmentDataJob job(String status, String jobType) {
        InvestmentDataJob job = new InvestmentDataJob();
        job.setUserId(7L);
        job.setAssetId(11L);
        job.setProductId(21L);
        job.setJobType(jobType);
        job.setStatus(status);
        job.setForceRefresh(false);
        job.setAttemptCount(0);
        job.setRecordCount(0);
        return job;
    }

    private static InvestmentProduct product(Long id, String productType, boolean coverageComplete) {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(id);
        product.setProductType(productType);
        product.setHistoryCoverageComplete(coverageComplete);
        return product;
    }

    private static InvestmentAsset asset(Long userId, Long assetId, Long productId) {
        InvestmentAsset asset = new InvestmentAsset();
        asset.setId(assetId);
        asset.setUserId(userId);
        asset.setProductId(productId);
        return asset;
    }
}
