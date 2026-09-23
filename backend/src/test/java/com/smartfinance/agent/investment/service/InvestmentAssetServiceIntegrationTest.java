package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetUpdateRequest;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.ChinaTradingCalendarService;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import com.smartfinance.agent.investment.service.InvestmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:investment_asset_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "investment.runtime.market.stock-refresh-start=00:00",
        "investment.runtime.market.stock-refresh-end=23:59"
})
@Import({com.smartfinance.agent.investment.service.InvestmentAssetServiceImpl.class,
        com.smartfinance.agent.investment.service.InvestmentDataJobService.class,
        com.smartfinance.agent.investment.service.FundClassificationService.class})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentAssetServiceIntegrationTest {

    @Autowired
    private InvestmentAssetService assetService;
    @Autowired
    private InvestmentService investmentService;
    @Autowired
    private ProductDailyQuoteMapper quoteMapper;
    @Autowired
    private InvestmentProductMapper productMapper;
    @Autowired
    private InvestmentDataJobMapper dataJobMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private AnalysisServiceClient analysisServiceClient;
    @MockBean
    private ChinaTradingCalendarService tradingCalendar;
    @MockBean
    private QuantBenchmarkProfileService benchmarkProfileService;

    @MockBean
    private com.smartfinance.agent.investment.service.InvestmentDetailCacheService detailCache;
    @MockBean
    private com.smartfinance.agent.investment.service.InvestmentQuoteCacheService quoteCache;

    @BeforeEach
    void setUpResolver() {
        when(tradingCalendar.isTradingDay(org.mockito.ArgumentMatchers.any(LocalDate.class))).thenReturn(true);
        when(analysisServiceClient.resolveProduct(anyString(), anyString()))
                .thenReturn(new AnalysisServiceClient.ResolvedProduct(
                        "STOCK", "600519", "贵州茅台", "SSE", "CNY", "AKSHARE",
                        LocalDate.of(2026, 7, 10), new BigDecimal("1204.98"),
                        new BigDecimal("1190.00"), new BigDecimal("14.98"), new BigDecimal("1.2588"),
                        null, null, null, null, null, null, null, null, List.of(),
                        LocalDate.of(2001, 8, 27)));
        when(analysisServiceClient.realtimeQuote(anyString(), anyString()))
                .thenReturn(new AnalysisServiceClient.RealtimeQuote(
                        "600519", "SSE", new BigDecimal("1198.72"),
                        LocalDate.of(2026, 7, 13), LocalDateTime.of(2026, 7, 13, 11, 23, 30),
                        new BigDecimal("1200"), new BigDecimal("-1.28"), new BigDecimal("-0.1067"),
                        new BigDecimal("1201"), new BigDecimal("1210"), new BigDecimal("1190"),
                        new BigDecimal("1000000"), new BigDecimal("1200000000"),
                        new BigDecimal("0.88"), new BigDecimal("1.23"), new BigDecimal("1.67"),
                        "TENCENT", List.of()));
    }

    @Test
    void createWithCodeOnly_shouldResolveProductWithoutHoldingFields() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        assertThat(asset.getName()).isEqualTo("贵州茅台");
        assertThat(asset.getQuantity()).isNull();
        assertThat(asset.getAverageCost()).isNull();
        assertThat(productMapper.selectById(asset.getProductId()).getInceptionDate())
                .isEqualTo(LocalDate.of(2001, 8, 27));
        assertThat(assetService.list(7L)).extracting("id").containsExactly(asset.getId());
        assertThatThrownBy(() -> assetService.get(8L, asset.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createStock_shouldImmediatelyFetchRealtimeQuoteWhenMetadataHasNoPrice() {
        when(analysisServiceClient.resolveProduct("STOCK", "600519"))
                .thenReturn(resolvedProduct("STOCK", "600519", "贵州茅台", "SSE", null));

        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        assertThat(asset.getLatestPrice()).isEqualByComparingTo("1198.72");
        assertThat(asset.getSyncStatus()).isEqualTo("SUCCESS");
        InvestmentDataJob job = jobFor(asset.getId());
        assertThat(job).isNotNull();
        assertThat(job)
                .extracting(InvestmentDataJob::getUserId,
                        InvestmentDataJob::getProductId,
                        InvestmentDataJob::getJobType,
                        InvestmentDataJob::getStatus,
                        InvestmentDataJob::getForceRefresh)
                .containsExactly(7L, asset.getProductId(), "STOCK_HISTORY", "QUEUED", true);
        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
    }

    @Test
    void createFund_shouldUseResolvedNavWithoutRepeatedMetadataFetch() {
        when(analysisServiceClient.resolveProduct("MUTUAL_FUND", "000001"))
                .thenReturn(resolvedFundProduct(
                        "000001", "华夏成长", "CN", "1.2345",
                        "混合型-偏股", "HYBRID_FUND"));

        var asset = assetService.create(7L, createRequest("FUND", "000001"));

        assertThat(asset.getLatestPrice()).isEqualByComparingTo("1.2345");
        assertThat(asset.getFundTypeRaw()).isEqualTo("混合型-偏股");
        assertThat(asset.getFundCategory()).isEqualTo("HYBRID_FUND");
        assertThat(asset.getSyncStatus()).isEqualTo("SUCCESS");
        InvestmentDataJob job = jobFor(asset.getId());
        assertThat(job).isNotNull();
        assertThat(job)
                .extracting(InvestmentDataJob::getUserId,
                        InvestmentDataJob::getProductId,
                        InvestmentDataJob::getJobType,
                        InvestmentDataJob::getStatus,
                        InvestmentDataJob::getForceRefresh)
                .containsExactly(7L, asset.getProductId(), "FUND_NAV_HISTORY", "QUEUED", true);
        verify(analysisServiceClient, times(1)).resolveProduct("MUTUAL_FUND", "000001");
        verifyNoInteractions(quoteCache);
    }

    @Test
    void refreshAll_shouldReuseFreshProductQuoteAcrossUsers() throws Exception {
        assetService.create(7L, createRequest("STOCK", "600519"));
        assetService.create(8L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient);
        when(analysisServiceClient.realtimeQuote(anyString(), anyString()))
                .thenReturn(realtimeQuote(LocalDateTime.now()));
        Thread.sleep(3100);

        assetService.refreshAll(7L, false);
        assetService.refreshAll(8L, false);

        verify(analysisServiceClient, times(1)).realtimeQuote("600519", "SSE");
    }

    @Test
    void concurrentForcedRefresh_shouldCoalesceSameProductRequest() throws Exception {
        assetService.create(7L, createRequest("STOCK", "600519"));
        assetService.create(8L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(analysisServiceClient.realtimeQuote("600519", "SSE")).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return realtimeQuote(LocalDateTime.now());
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> assetService.refreshAll(7L, true));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> assetService.refreshAll(8L, true));
            Thread.sleep(100);
            releaseProvider.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(analysisServiceClient, times(1)).realtimeQuote("600519", "SSE");
    }

    @Test
    void redisHit_shouldPersistQuoteWithoutCallingProviderAndForceShouldBypassIt() throws Exception {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient, quoteCache);
        Thread.sleep(3100);
        var quote = realtimeQuote(LocalDateTime.now());
        var now = java.time.Instant.now();
        when(quoteCache.get("SSE", "600519")).thenReturn(
                new com.smartfinance.agent.investment.service.InvestmentQuoteCacheService.Entry(
                        now, now.plusSeconds(3), quote));

        var refreshed = assetService.refreshAll(7L, false).get(0);

        assertThat(refreshed.getLatestPrice()).isEqualByComparingTo(quote.latestPrice());
        assertThat(assetService.get(7L, asset.getId()).getLatestPrice()).isEqualByComparingTo(quote.latestPrice());
        assertThat(jdbc.queryForObject("select count(*) from product_daily_quote where product_id = ? and trade_date = ?",
                Integer.class, asset.getProductId(), quote.dataDate())).isEqualTo(1);
        verifyNoInteractions(analysisServiceClient);
        verify(quoteCache).get("SSE", "600519");
        when(analysisServiceClient.realtimeQuote("600519", "SSE")).thenReturn(quote);

        assetService.refreshAll(7L, true);

        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
        verify(quoteCache, times(1)).get("SSE", "600519");
        verify(quoteCache).put(org.mockito.ArgumentMatchers.eq("SSE"),
                org.mockito.ArgumentMatchers.eq("600519"), org.mockito.ArgumentMatchers.eq(quote),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class));
    }

    @Test
    void redisHit_shouldNotExtendLocalFreshnessPastItsExpiry() throws Exception {
        assetService.create(7L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient, quoteCache);
        Thread.sleep(3100);
        var quote = realtimeQuote(LocalDateTime.now());
        var now = java.time.Instant.now();
        when(quoteCache.get("SSE", "600519")).thenReturn(
                new com.smartfinance.agent.investment.service.InvestmentQuoteCacheService.Entry(
                        now.minusSeconds(2), now.plusMillis(100), quote), null);
        when(analysisServiceClient.realtimeQuote("600519", "SSE")).thenReturn(quote);

        assetService.refreshAll(7L, false);
        Thread.sleep(150);
        assetService.refreshAll(7L, false);

        verify(quoteCache, times(2)).get("SSE", "600519");
        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
    }

    @Test
    void refreshFailure_shouldKeepLastSuccessfulQuoteAndMarkAssetFailed() {
        var created = assetService.create(7L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient, quoteCache);
        when(analysisServiceClient.realtimeQuote("600519", "SSE"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        var refreshed = assetService.refreshAll(7L, true).get(0);

        assertThat(refreshed.getLatestPrice()).isEqualByComparingTo(created.getLatestPrice());
        assertThat(refreshed.getSyncStatus()).isEqualTo("FAILED");
        assertThat(refreshed.getSyncError()).contains("provider unavailable");
        verifyNoInteractions(quoteCache);
    }

    @Test
    void cachedPartialFundOutcome_shouldRemainPartialWithoutAnotherProviderCall() {
        when(analysisServiceClient.resolveProduct("MUTUAL_FUND", "000001"))
                .thenReturn(resolvedProduct("MUTUAL_FUND", "000001", "华夏成长", "CN", null));
        var created = assetService.create(7L, createRequest("FUND", "000001"));
        assertThat(created.getSyncStatus()).isEqualTo("PARTIAL");
        reset(analysisServiceClient);

        var cached = assetService.refreshAll(7L, false).get(0);

        assertThat(cached.getSyncStatus()).isEqualTo("PARTIAL");
        verifyNoInteractions(analysisServiceClient);
    }

    @Test
    void failedRefresh_shouldRetryOnNextNonForcedRequest() {
        assetService.create(7L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient);
        when(analysisServiceClient.realtimeQuote("600519", "SSE"))
                .thenThrow(new IllegalStateException("temporary failure"));
        assertThat(assetService.refreshAll(7L, true).get(0).getSyncStatus()).isEqualTo("FAILED");
        reset(analysisServiceClient);
        when(analysisServiceClient.realtimeQuote("600519", "SSE"))
                .thenReturn(realtimeQuote(LocalDateTime.now()));

        var retried = assetService.refreshAll(7L, false).get(0);

        assertThat(retried.getSyncStatus()).isEqualTo("SUCCESS");
        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
    }

    @Test
    void closedMarket_shouldReuseCloseButForcedRefreshStillCallsProvider() throws Exception {
        assetService.create(7L, createRequest("STOCK", "600519"));
        reset(analysisServiceClient);
        when(tradingCalendar.isTradingDay(org.mockito.ArgumentMatchers.any(LocalDate.class))).thenReturn(false);
        Thread.sleep(3100);

        assetService.refreshAll(7L, false);
        assetService.refreshAll(7L, false);
        verifyNoInteractions(analysisServiceClient);

        when(analysisServiceClient.realtimeQuote("600519", "SSE"))
                .thenReturn(realtimeQuote(LocalDateTime.now()));
        assetService.refreshAll(7L, true);
        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
    }

    @Test
    void refreshAll_shouldRefreshDifferentProductsConcurrently() throws Exception {
        when(analysisServiceClient.resolveProduct("STOCK", "000001"))
                .thenReturn(resolvedProduct("STOCK", "000001", "平安银行", "SZSE", null));
        when(analysisServiceClient.realtimeQuote("000001", "SZSE"))
                .thenReturn(realtimeQuote("000001", "SZSE", LocalDateTime.now()));
        assetService.create(7L, createRequest("STOCK", "600519"));
        assetService.create(7L, createRequest("STOCK", "000001"));
        reset(analysisServiceClient);
        CountDownLatch bothProvidersEntered = new CountDownLatch(2);
        when(analysisServiceClient.realtimeQuote(anyString(), anyString())).thenAnswer(invocation -> {
            bothProvidersEntered.countDown();
            assertThat(bothProvidersEntered.await(2, TimeUnit.SECONDS)).isTrue();
            return realtimeQuote(invocation.getArgument(0), invocation.getArgument(1), LocalDateTime.now());
        });

        var refreshed = assetService.refreshAll(7L, true);

        assertThat(refreshed).hasSize(2).allMatch(asset -> "SUCCESS".equals(asset.getSyncStatus()));
        verify(analysisServiceClient).realtimeQuote("600519", "SSE");
        verify(analysisServiceClient).realtimeQuote("000001", "SZSE");
    }

    @Test
    void updateAndDelete_shouldUseReversalAndReplacementTransactions() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        assetService.update(7L, asset.getId(), updateRequest("10", "1500", "首次录入"));
        var updated = assetService.update(7L, asset.getId(), updateRequest("8", "1480", "调整持仓"));

        verify(detailCache, times(2)).evict(7L, asset.getId());

        assertThat(updated.getQuantity()).isEqualByComparingTo("8");
        assertThat(updated.getAverageCost()).isEqualByComparingTo("1480");
        assertThat(updated.getMarketValueCny()).isEqualByComparingTo("9589.76");
        assertThat(updated.getUnrealizedPnlCny()).isEqualByComparingTo("-2250.24");
        assertThat(updated.getHoldingReturnPercent()).isEqualByComparingTo("-19.00540500");
        assertThat(investmentService.listTransactions(7L, updated.getAccountId(), 20))
                .extracting("eventType").containsExactly("TRANSFER_IN", "REVERSAL", "TRANSFER_IN");

        assetService.delete(7L, asset.getId());

        verify(detailCache, times(3)).evict(7L, asset.getId());

        assertThat(assetService.list(7L)).isEmpty();
        assertThat(investmentService.listTransactions(7L, updated.getAccountId(), 20))
                .extracting("eventType").containsExactly("REVERSAL", "TRANSFER_IN", "REVERSAL", "TRANSFER_IN");
    }

    @Test
    void quoteRefresh_shouldNotRestoreAStaleHoldingTransaction() throws Exception {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));
        assetService.update(7L, asset.getId(), updateRequest("10", "1500", "首次录入"));
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        when(analysisServiceClient.realtimeQuote("600519", "SSE")).thenAnswer(invocation -> {
            refreshStarted.countDown();
            assertThat(releaseRefresh.await(2, TimeUnit.SECONDS)).isTrue();
            return realtimeQuote(LocalDateTime.now());
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var refresh = executor.submit(() -> assetService.refresh(7L, asset.getId(), true));
            assertThat(refreshStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assetService.update(7L, asset.getId(), updateRequest("8", "1480", "调整持仓"));
            releaseRefresh.countDown();
            refresh.get(2, TimeUnit.SECONDS);

            var latest = assetService.update(7L, asset.getId(),
                    updateRequest("7", "1470", "再次调整"));
            assertThat(latest.getQuantity()).isEqualByComparingTo("7");
        } finally {
            releaseRefresh.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void update_shouldRecoverAStaleCurrentTransactionReference() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));
        assetService.update(7L, asset.getId(), updateRequest("10", "1500", "首次录入"));
        assetService.update(7L, asset.getId(), updateRequest("8", "1480", "调整持仓"));
        var transactions = investmentService.listTransactions(7L, asset.getAccountId(), 20);
        Long staleTransactionId = transactions.stream()
                .filter(item -> "TRANSFER_IN".equals(item.getEventType()))
                .map(item -> item.getId())
                .min(Long::compareTo)
                .orElseThrow();
        jdbc.update("UPDATE investment_asset SET current_transaction_id = ? WHERE id = ?",
                staleTransactionId, asset.getId());

        var updated = assetService.update(7L, asset.getId(),
                updateRequest("7", "1470", "修复后调整"));
        var position = investmentService.overview(7L).getPositions().stream()
                .filter(item -> asset.getProductId().equals(item.getProductId()))
                .findFirst().orElseThrow();

        assertThat(updated.getQuantity()).isEqualByComparingTo("7");
        assertThat(position.getQuantity()).isEqualByComparingTo("7");
    }

    @Test
    void sync_shouldRefreshTheSelectedAssetImmediately() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));

        var synced = assetService.sync(7L, asset.getId());

        assertThat(synced.getSyncStatus()).isEqualTo("SUCCESS");
        assertThat(synced.getLatestPrice()).isEqualByComparingTo("1198.72");
        assertThat(synced.getDataDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(synced.getFetchedAt()).isEqualTo(LocalDateTime.of(2026, 7, 13, 11, 23, 30));
        assertThat(synced.getChangeAmount()).isEqualByComparingTo("-1.28");
        assertThat(synced.getChangePercent()).isEqualByComparingTo("-0.1067");
        assertThat(synced.getTurnoverRate()).isEqualByComparingTo("0.88");
        assertThat(synced.getVolumeRatio()).isEqualByComparingTo("1.23");
    }

    @Test
    void list_shouldDeriveChangeMetricsForExistingHistoryRows() {
        var asset = assetService.create(7L, createRequest("STOCK", "600519"));
        insertHistoryQuote(asset.getProductId(), LocalDate.of(2026, 7, 15), "100");
        insertHistoryQuote(asset.getProductId(), LocalDate.of(2026, 7, 16), "110");

        var listed = assetService.list(7L).get(0);

        assertThat(listed.getLatestPrice()).isEqualByComparingTo("110");
        assertThat(listed.getPreviousClose()).isEqualByComparingTo("100");
        assertThat(listed.getChangeAmount()).isEqualByComparingTo("10");
        assertThat(listed.getChangePercent()).isEqualByComparingTo("10");
    }

    private void insertHistoryQuote(Long productId, LocalDate tradeDate, String close) {
        ProductDailyQuote quote = new ProductDailyQuote();
        quote.setProductId(productId);
        quote.setTradeDate(tradeDate);
        quote.setOpenPrice(new BigDecimal(close));
        quote.setHighPrice(new BigDecimal(close));
        quote.setLowPrice(new BigDecimal(close));
        quote.setClosePrice(new BigDecimal(close));
        quote.setAdjustType("QFQ");
        quote.setSource("TEST");
        quote.setAdapterVersion("1");
        quote.setSyncedAt(LocalDateTime.of(2026, 7, 16, 15, 0));
        quoteMapper.insert(quote);
    }

    private InvestmentDataJob jobFor(Long assetId) {
        return dataJobMapper.selectList(null).stream()
                .filter(job -> assetId.equals(job.getAssetId()))
                .findFirst()
                .orElse(null);
    }

    private static InvestmentAssetCreateRequest createRequest(String type, String code) {
        InvestmentAssetCreateRequest request = new InvestmentAssetCreateRequest();
        request.setProductType(type);
        request.setCode(code);
        return request;
    }

    private static InvestmentAssetUpdateRequest updateRequest(String quantity, String cost, String note) {
        InvestmentAssetUpdateRequest request = new InvestmentAssetUpdateRequest();
        request.setQuantity(new BigDecimal(quantity));
        request.setAverageCost(new BigDecimal(cost));
        request.setNote(note);
        return request;
    }

    private static AnalysisServiceClient.ResolvedProduct resolvedProduct(
            String type, String code, String name, String market, String latestPrice) {
        BigDecimal price = latestPrice == null ? null : new BigDecimal(latestPrice);
        return new AnalysisServiceClient.ResolvedProduct(
                type, code, name, market, "CNY", "AKSHARE",
                price == null ? null : LocalDate.of(2026, 7, 22), price, List.of());
    }

    private static AnalysisServiceClient.ResolvedProduct resolvedFundProduct(
            String code, String name, String market, String latestPrice,
            String rawType, String category) {
        BigDecimal price = latestPrice == null ? null : new BigDecimal(latestPrice);
        return new AnalysisServiceClient.ResolvedProduct(
                "MUTUAL_FUND", code, name, market, "CNY", "AKSHARE",
                price == null ? null : LocalDate.of(2026, 7, 22), price,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, rawType, category,
                "AKSHARE_FUND_NAME_EM", "fund-classification-v1");
    }

    private static AnalysisServiceClient.RealtimeQuote realtimeQuote(LocalDateTime fetchedAt) {
        return realtimeQuote("600519", "SSE", fetchedAt);
    }

    private static AnalysisServiceClient.RealtimeQuote realtimeQuote(
            String code, String market, LocalDateTime fetchedAt) {
        return new AnalysisServiceClient.RealtimeQuote(
                code, market, new BigDecimal("1198.72"), fetchedAt.toLocalDate(), fetchedAt,
                new BigDecimal("1200"), new BigDecimal("-1.28"), new BigDecimal("-0.1067"),
                new BigDecimal("1201"), new BigDecimal("1210"), new BigDecimal("1190"),
                new BigDecimal("1000000"), new BigDecimal("1200000000"),
                new BigDecimal("0.88"), new BigDecimal("1.23"), new BigDecimal("1.67"),
                "TENCENT", List.of());
    }
}
