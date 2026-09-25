package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketDataInfrastructureTest {
    private Path file;
    private JdbcTemplate db;
    private MarketDataSyncService sync;
    private MarketDataService market;
    private InvestmentProductMapper products;
    private AnalysisServiceClient analysis;
    private UnifiedMarketDataIngestionService ingestion;

    @BeforeEach
    void setup() throws Exception {
        file = Files.createTempFile("market-data-", ".db");
        var source = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath(), "", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db = new JdbcTemplate(source);
        products = mock(InvestmentProductMapper.class);
        analysis = mock(AnalysisServiceClient.class);
        ingestion = mock(UnifiedMarketDataIngestionService.class);
        sync = new MarketDataSyncService(db, products, analysis, ingestion,
                new QuoteSeriesCoverageService(db),
                new QuoteSeriesPolicy(InvestmentSyncWorkerTest.runtimeProperties(14)),
                new DataSourceTransactionManager(source), true, 10, 90);
        market = new MarketDataService(db, analysis);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(10,'STOCK','NASDAQ','TEST','Test Corp','USD','ACTIVE')");
        db.update("INSERT INTO market_data_scope_product(product_id,scope_group,updated_at) "
                + "VALUES(10,'US',CURRENT_TIMESTAMP)");
        var product = new InvestmentProduct();
        product.setId(10L); product.setProductType("STOCK"); product.setMarket("NASDAQ");
        product.setCode("TEST"); product.setStatus("ACTIVE");
        when(products.selectById(10L)).thenReturn(product);
    }

    @AfterEach
    void cleanup() throws Exception { Files.deleteIfExists(file); }

    private Map<String, Object> quote(LocalDate day) {
        return Map.of("data_date", day.toString(), "close", "101.50", "volume", "1000");
    }

    @Test
    void migrationKeepsExistingQuotesAndSkipsPersonalMarketJobs() throws Exception {
        Path legacy = Files.createTempFile("market-data-legacy-", ".db");
        try {
            var source = new DriverManagerDataSource("jdbc:sqlite:" + legacy.toAbsolutePath(), "", "");
            Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite")
                    .target("3").load().migrate();
            JdbcTemplate old = new JdbcTemplate(source);
            old.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,"
                    + "inception_date) VALUES(101,'MUTUAL_FUND','FUND_CN','000001','Personal','CNY',"
                    + "'ACTIVE','2020-01-01')");
            old.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,"
                    + "total_return_index,adjust_type,source) VALUES"
                    + "(101,'2024-01-02',1.5,1.8,'NONE','TEST'),"
                    + "(101,'2024-01-03',1.6,NULL,'NONE','TEST')");
            old.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                    + "attempt_count,updated_at) VALUES(101,'BACKFILL','QUEUED','2020-01-01',"
                    + "'2024-01-03',0,CURRENT_TIMESTAMP)");

            Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite")
                    .load().migrate();

            assertThat(old.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=101",
                    Integer.class)).isEqualTo(2);
            assertThat(old.queryForObject("SELECT status FROM market_data_job WHERE product_id=101",
                    String.class)).isEqualTo("SKIPPED");
            assertThat(old.queryForList("SELECT dataset_type,observations,status,reason "
                    + "FROM product_quote_coverage WHERE product_id=101 ORDER BY dataset_type"))
                    .extracting(row -> row.get("dataset_type") + ":" + row.get("observations")
                            + ":" + row.get("status") + ":" + row.get("reason"))
                    .containsExactly("NAV:2:INCOMPLETE:LEGACY_UNVERIFIED",
                            "TOTAL_RETURN_INDEX:1:INCOMPLETE:LEGACY_UNVERIFIED");
        } finally {
            Files.deleteIfExists(legacy);
        }
    }

    @Test
    void usCatalogKeepsCuratedEtfSeparateFromStocks() {
        when(analysis.marketCatalog("US")).thenReturn(List.of(Map.of(
                "productType", "ETF", "market", "NYSE", "exchange", "NYSE",
                "code", "SPY", "name", "SPDR S&P 500 ETF", "currency", "USD",
                "source", "AKSHARE_US_SPOT_CURATED_ETF")));
        assertThat(sync.importCatalog("US")).isEqualTo(1);
        assertThat(db.queryForMap("SELECT product_type,market,code FROM investment_product WHERE code='SPY'"))
                .containsEntry("product_type", "ETF").containsEntry("market", "NYSE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job j JOIN investment_product p "
                + "ON p.id=j.product_id WHERE p.code='SPY' AND j.job_type='BACKFILL'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void catalogRefreshRemovesProductsOutsideTheCurrentResearchScope() {
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(10,'BACKFILL','QUEUED',"
                + "'2024-01-01','2024-12-31',0,CURRENT_TIMESTAMP)");
        when(analysis.marketCatalog("US")).thenReturn(List.of(Map.of(
                "productType", "ETF", "market", "NYSE", "exchange", "NYSE",
                "code", "SPY", "name", "SPDR S&P 500 ETF", "currency", "USD",
                "source", "AKSHARE_US_SPOT_CURATED_ETF")));

        sync.importCatalog("US");

        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_scope_product WHERE product_id=10",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10",
                String.class)).isEqualTo("SKIPPED");
    }

    @Test
    void catalogBackfillDoesNotSweepUnrelatedPersonalProducts() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(11,'MUTUAL_FUND','FUND_CN','000001','Personal Fund','CNY','ACTIVE')");
        when(analysis.marketCatalog("INDEX")).thenReturn(List.of(Map.of(
                "productType", "INDEX", "market", "CN_INDEX", "code", "CN_INDEX:000300",
                "name", "CSI 300", "currency", "CNY", "source", "INDEX_WATCHLIST_REGISTRY")));

        sync.importCatalog("INDEX");

        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE product_id=11", Integer.class))
                .isZero();
    }

    @Test
    void fundNavAndTotalReturnHaveIndependentCoverage() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(11,'MUTUAL_FUND','FUND_CN','000001','Personal Fund','CNY','ACTIVE')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(11,'2024-01-02',1.5,'NONE','TEST')");
        QuoteSeriesCoverageService coverage = new QuoteSeriesCoverageService(db);
        LocalDate day = LocalDate.of(2024, 1, 2);

        coverage.record(11L, "NONE", "NAV", day, day, true, "TEST", "v1", null);
        coverage.record(11L, "NONE", "TOTAL_RETURN_INDEX", day, day, true, "TEST", "v1", null);

        assertThat(coverage.find(11L, "NONE", "NAV").status()).isEqualTo("COMPLETE");
        assertThat(coverage.find(11L, "NONE", "TOTAL_RETURN_INDEX").status()).isEqualTo("INCOMPLETE");
    }

    @Test
    void coverageDoesNotClaimCompleteWhenProviderStopsBeforeTarget() {
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(10,'2024-01-02',101.5,'NONE','TEST')");
        QuoteSeriesCoverageService.Coverage state = new QuoteSeriesCoverageService(db).record(
                10L, "NONE", "PRICE", LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 3, 1), true, "TEST", "v1", null);
        assertThat(state.status()).isEqualTo("INCOMPLETE");
        assertThat(state.reason()).isEqualTo("PROVIDER_END_BEFORE_TARGET");
        QuoteSeriesCoverageService.Coverage narrower = new QuoteSeriesCoverageService(db).record(
                10L, "NONE", "PRICE", LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 1, 10), true, "TEST", "v2", null);
        assertThat(narrower.targetDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(narrower.status()).isEqualTo("INCOMPLETE");
    }

    @Test
    void marketWorkerDelegatesQuotesToUnifiedIngestion() {
        sync.queueBackfill(10L);
        sync.processNext();

        verify(ingestion).ingest(eq(products.selectById(10L)), any(), any(), eq("NONE"), any());
        verify(ingestion).ingest(eq(products.selectById(10L)), any(), any(), eq("QFQ"), any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=10",
                Integer.class)).isZero();
    }

    @Test
    void backfillPersistsCheckpointWithoutDirectQuoteWrites() {
        sync.queueBackfill(10L);
        sync.processNext();
        sync.processNext();
        verify(ingestion, times(2)).ingest(any(), any(), any(), eq("NONE"), any());
        verify(ingestion, times(2)).ingest(any(), any(), any(), eq("QFQ"), any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=10", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=10", String.class)).isNotNull();
    }

    @Test
    void dailyUpdateStartsAfterLastStoredDateAndDoesNotRequeueSameTarget() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        seedCoverage(target.minusDays(1));
        when(ingestion.ingest(any(), eq(target), eq(target), any(), any()))
                .thenAnswer(invocation -> {
                    seedCoverage(target);
                    return new UnifiedMarketDataIngestionService.Result(2, target, target, true, "v1", "COMPLETE");
                });
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isEqualTo(1);
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("SUCCEEDED");
        verify(ingestion).ingest(any(), eq(target), eq(target), eq("NONE"), any());
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isZero();
    }

    @Test
    void dailyUpdateWaitsForBackfillToFinish() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        sync.queueBackfill(10L);
        seedCoverage(target.minusDays(2));
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE product_id=10 "
                + "AND job_type='DAILY_UPDATE'", Integer.class)).isZero();
    }

    @Test
    void providerFailureWaitsThenRetriesWithoutAdvancingCheckpoint() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        seedCoverage(target.minusDays(1));
        sync.queueDaily(java.util.Set.of("NASDAQ"), target);
        when(ingestion.ingest(any(), any(), any(), eq("NONE"), any()))
                .thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(new UnifiedMarketDataIngestionService.Result(2, target, target,
                        true, "v1", "COMPLETE"));
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("RETRY_WAIT");
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=10", String.class)).isNull();
        db.update("UPDATE market_data_job SET next_retry_at=? WHERE product_id=10", "2000-01-01T00:00:00");
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("SUCCEEDED");
    }

    private void seedCoverage(LocalDate day) {
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(10,?,101.5,'NONE','TEST') ON CONFLICT(product_id,trade_date,adjust_type) DO NOTHING", day);
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(10,?,101.5,'QFQ','TEST') ON CONFLICT(product_id,trade_date,adjust_type) DO NOTHING", day);
        QuoteSeriesCoverageService state = new QuoteSeriesCoverageService(db);
        state.record(10L, "NONE", "PRICE", day, day, true, "TEST", "v1", null);
        state.record(10L, "QFQ", "PRICE", day, day, true, "TEST", "v1", null);
    }

    @Test
    void productSearchIsPagedAndMarketCoverageDoesNotGuessUsHolidays() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(11,'STOCK','NYSE','OTHER','Other Corp','USD','ACTIVE')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(10,'2024-01-02',101.5,'NONE','TEST')");
        Map<String, Object> page = market.products("Test", null, "US", "STOCK", "ACTIVE", 1, 1);
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat((List<?>) page.get("items")).hasSize(1);
        assertThat(market.coverage(10L, "NONE")).containsEntry("coverageStatus", "CALENDAR_UNAVAILABLE")
                .containsEntry("dataQualityStatus", "NOT_EVALUATED")
                .containsEntry("coverageRatio", null);
    }

    @Test
    void chinaCoverageUsesProviderTradingDatesInsteadOfWeekdays() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(12,'STOCK','SSE','600001','CN Test','CNY','ACTIVE')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,close_price,adjust_type,source) "
                + "VALUES(12,'2024-01-02',10,'NONE','TEST'),(12,'2024-01-04',11,'NONE','TEST')");
        when(analysis.aShareTradingDates(2024)).thenReturn(List.of(
                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 4)));
        assertThat(market.coverage(12L, "NONE"))
                .containsEntry("missingTradingDays", 1L)
                .containsEntry("coverageStatus", "GAPS")
                .containsEntry("dataQualityStatus", "NOT_EVALUATED");
    }
}
