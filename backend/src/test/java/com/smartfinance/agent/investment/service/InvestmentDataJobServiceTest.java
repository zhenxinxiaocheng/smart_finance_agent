package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentDataJobServiceTest {

    @BeforeAll
    static void initializeAssetMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "product-analysis-job-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, InvestmentAsset.class);
    }

    private InvestmentDataJobMapper mapper;
    private InvestmentProductMapper productMapper;
    private ProductDailyQuoteMapper quoteMapper;
    private InvestmentDataJobService service;

    @BeforeEach
    void setUp() {
        mapper = mock(InvestmentDataJobMapper.class);
        when(mapper.requeueForRefresh(any())).thenReturn(1);
        when(mapper.requeueTerminalIncremental(any())).thenReturn(1);
        productMapper = mock(InvestmentProductMapper.class);
        quoteMapper = mock(ProductDailyQuoteMapper.class);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(21L);
        product.setProductType("STOCK");
        product.setHistoryEndDate(java.time.LocalDate.of(2026, 7, 30));
        when(productMapper.selectById(21L)).thenReturn(product);
        when(quoteMapper.selectCount(any())).thenReturn(10L);
        service = new InvestmentDataJobService(mapper, productMapper, quoteMapper);
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
        verify(mapper).requeueForRefresh(existing.getId());
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
        verify(mapper, never()).requeueForRefresh(any());
    }

    @Test
    void automaticRecoveryDoesNotLoopPartialJob() {
        InvestmentDataJob existing = job("PARTIAL", "STOCK_HISTORY");
        existing.setFinishedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK").getStatus())
                .isEqualTo("PARTIAL");
        verify(mapper, never()).requeueForRefresh(any());
    }

    @Test
    void automaticRecoveryRequeuesACompletedJobIncrementally() {
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        existing.setRecordCount(526);
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 22));
        when(mapper.selectOne(any())).thenReturn(existing);

        // 只有「分析已过期」时才增量重排，绝不因此做全量回补。
        InvestmentDataJob job = service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK", true);

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getForceRefresh()).isFalse();
        assertThat(job.getRecordCount()).isZero();
        verify(mapper).requeueTerminalIncremental(existing.getId());
    }

    @Test
    void changedDataRecoversAFailedJobIncrementally() {
        InvestmentDataJob existing = job("FAILED", "STOCK_HISTORY");
        existing.setId(91L);
        existing.setAttemptCount(3);
        when(mapper.selectOne(any())).thenReturn(existing);

        InvestmentDataJob result = service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK", true);

        assertThat(result.getStatus()).isEqualTo("QUEUED");
        assertThat(result.getForceRefresh()).isFalse();
        assertThat(result.getAttemptCount()).isZero();
        verify(mapper).requeueTerminalIncremental(91L);
        verify(mapper, never()).requeueForRefresh(any());
    }

    @Test
    void productAnalysisQueueUsesRealUndeletedAssetIdsAndPreservesActiveJobs() {
        InvestmentAssetMapper assetMapper = mock(InvestmentAssetMapper.class);
        InvestmentAsset first = new InvestmentAsset();
        first.setId(11L);
        first.setUserId(7L);
        first.setProductId(21L);
        InvestmentAsset second = new InvestmentAsset();
        second.setId(12L);
        second.setUserId(8L);
        second.setProductId(21L);
        when(assetMapper.selectList(any())).thenReturn(List.of(first, second));
        InvestmentDataJob active = job("RUNNING", "STOCK_HISTORY");
        active.setId(91L);
        active.setLeaseToken("active-worker");
        InvestmentDataJob failed = job("FAILED", "STOCK_HISTORY");
        failed.setId(92L);
        failed.setAssetId(12L);
        when(mapper.selectOne(any())).thenReturn(active, failed);
        InvestmentDataJobService productService = spy(new InvestmentDataJobService(
                mapper, productMapper, quoteMapper, assetMapper));
        InvestmentDetailCacheService cache = mock(InvestmentDetailCacheService.class);
        productService.configureDetailCache(cache);

        productService.queueAnalysisForProduct(21L);

        verify(productService).ensureRecoveryQueued(7L, 11L, 21L, "STOCK", true);
        verify(productService).ensureRecoveryQueued(8L, 12L, 21L, "STOCK", true);
        verify(cache).evict(7L, 11L);
        verify(cache).evict(8L, 12L);
        assertThat(active.getStatus()).isEqualTo("RUNNING");
        assertThat(active.getLeaseToken()).isEqualTo("active-worker");
        verify(mapper, never()).requeueTerminalIncremental(91L);
        verify(mapper).requeueTerminalIncremental(92L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<InvestmentAsset>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(assetMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("product_id", "deleted");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(21L, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "PARTIAL", "CANCELLED", "QUEUED", "RETRY_WAIT", "RUNNING"})
    void repeatedStartupPreservesExistingJobsAndDetailCaches(String status) {
        InvestmentAssetMapper assets = mock(InvestmentAssetMapper.class);
        InvestmentAsset asset = new InvestmentAsset();
        asset.setId(11L);
        asset.setUserId(7L);
        asset.setProductId(21L);
        when(assets.selectList(any())).thenReturn(List.of(asset));
        InvestmentDataJob existing = job(status, "STOCK_HISTORY");
        existing.setId(91L);
        existing.setRecordCount(526);
        existing.setAttemptCount(3);
        existing.setErrorMessage("previous outcome");
        LocalDateTime retry = LocalDateTime.of(2026, 10, 15, 9, 0);
        existing.setNextRetryAt(retry);
        existing.setLeaseToken("existing-worker");
        existing.setLeaseUntil(retry.plusMinutes(2));
        when(mapper.selectOne(any())).thenReturn(existing);
        InvestmentDataJobService startup = new InvestmentDataJobService(mapper, productMapper, quoteMapper, assets);
        MarketDataDemandService demand = mock(MarketDataDemandService.class);
        InvestmentDetailCacheService cache = mock(InvestmentDetailCacheService.class);
        startup.configureDemand(demand);
        startup.configureDetailCache(cache);

        startup.resumeHeldData((user, id) -> false);
        startup.resumeHeldData((user, id) -> false);

        assertThat(existing.getStatus()).isEqualTo(status);
        assertThat(existing.getRecordCount()).isEqualTo(526);
        assertThat(existing.getAttemptCount()).isEqualTo(3);
        assertThat(existing.getErrorMessage()).isEqualTo("previous outcome");
        assertThat(existing.getNextRetryAt()).isEqualTo(retry);
        assertThat(existing.getLeaseToken()).isEqualTo("existing-worker");
        assertThat(existing.getLeaseUntil()).isEqualTo(retry.plusMinutes(2));
        verify(demand, times(2)).asset(7L, 11L, 21L);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).requeueTerminalIncremental(any());
        verify(mapper, never()).requeueForRefresh(any());
        verify(mapper, never()).updateById(any());
        verifyNoInteractions(cache);
    }

    @Test
    void startupCreatesMissingJobsForEachHeldAssetWithoutForcingAFullRepair() {
        InvestmentAssetMapper assets = mock(InvestmentAssetMapper.class);
        InvestmentAsset first = new InvestmentAsset();
        first.setId(11L);
        first.setUserId(7L);
        first.setProductId(21L);
        InvestmentAsset second = new InvestmentAsset();
        second.setId(12L);
        second.setUserId(8L);
        second.setProductId(21L);
        when(assets.selectList(any())).thenReturn(List.of(first, second));
        InvestmentDataJobService startup = new InvestmentDataJobService(mapper, productMapper, quoteMapper, assets);
        startup.configureDemand(mock(MarketDataDemandService.class));
        InvestmentDetailCacheService cache = mock(InvestmentDetailCacheService.class);
        startup.configureDetailCache(cache);

        startup.resumeHeldData((user, id) -> false);

        ArgumentCaptor<InvestmentDataJob> created = ArgumentCaptor.forClass(InvestmentDataJob.class);
        verify(mapper, times(2)).insert(created.capture());
        assertThat(created.getAllValues()).extracting(InvestmentDataJob::getAssetId).containsExactly(11L, 12L);
        assertThat(created.getAllValues()).extracting(InvestmentDataJob::getUserId).containsExactly(7L, 8L);
        assertThat(created.getAllValues()).allSatisfy(queued -> {
            assertThat(queued.getProductId()).isEqualTo(21L);
            assertThat(queued.getJobType()).isEqualTo("STOCK_HISTORY");
            assertThat(queued.getStatus()).isEqualTo("QUEUED");
            assertThat(queued.getForceRefresh()).isFalse();
        });
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARING", "READY", "WAITING", "BLOCKED"})
    void startupRecoversOnlyMissingOrIncompatibleAnalysis(String state) {
        InvestmentAssetMapper assets = mock(InvestmentAssetMapper.class);
        InvestmentAsset asset = new InvestmentAsset();
        asset.setId(11L); asset.setUserId(7L); asset.setProductId(21L);
        when(assets.selectList(any())).thenReturn(List.of(asset));
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        existing.setId(91L);
        when(mapper.selectOne(any())).thenReturn(existing);
        var cache = mock(InvestmentDetailCacheService.class);
        var startup = new InvestmentDataJobService(mapper, productMapper, quoteMapper, assets);
        startup.configureDemand(mock(MarketDataDemandService.class));
        startup.configureDetailCache(cache);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiPredicate<Long, Long> needsRecovery = (user, id) -> {
            assertThat(user).isEqualTo(7L); assertThat(id).isEqualTo(11L);
            checks.incrementAndGet();
            return "PREPARING".equals(state);
        };
        startup.resumeHeldData(needsRecovery);
        startup.resumeHeldData(needsRecovery);
        if ("PREPARING".equals(state)) {
            verify(mapper).requeueTerminalIncremental(91L);
            verify(cache).evict(7L, 11L);
            assertThat(existing.getForceRefresh()).isFalse();
            assertThat(checks.get()).isEqualTo(1);
        } else {
            verify(mapper, never()).requeueTerminalIncremental(any());
            verifyNoInteractions(cache);
        }
        verify(mapper, never()).requeueForRefresh(any());
    }

    @Test
    void firstDemandJobDoesNotTurnMissingLocalHistoryIntoAnExplicitFullRepair() {
        service.configureDemand(mock(MarketDataDemandService.class));
        when(quoteMapper.selectCount(any())).thenReturn(0L);

        InvestmentDataJob queued = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(queued.getForceRefresh()).isFalse();
    }

    @Test
    void noLocalHistoryStillQueuesFirstBackfill() {
        when(quoteMapper.selectCount(any())).thenReturn(0L);

        InvestmentDataJob queued = service.ensureQueued(7L, 11L, 21L, "STOCK", false);

        assertThat(queued.getForceRefresh()).isTrue();
    }

    @Test
    void completedJobIsNotRequeuedWithoutNewQuotes() {
        InvestmentDataJob existing = job("SUCCEEDED", "STOCK_HISTORY");
        when(quoteMapper.latestTradeDate(21L, "QFQ")).thenReturn(LocalDate.of(2026, 7, 22));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.ensureRecoveryQueued(7L, 11L, 21L, "STOCK").getStatus())
                .isEqualTo("SUCCEEDED");
        verify(mapper, never()).requeueTerminalIncremental(any());
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

}
