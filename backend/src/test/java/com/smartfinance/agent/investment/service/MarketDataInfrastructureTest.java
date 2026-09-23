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

    @BeforeEach
    void setup() throws Exception {
        file = Files.createTempFile("market-data-", ".db");
        var source = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath(), "", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db = new JdbcTemplate(source);
        products = mock(InvestmentProductMapper.class);
        analysis = mock(AnalysisServiceClient.class);
        sync = new MarketDataSyncService(db, products, analysis, mock(InvestmentDataQualityService.class),
                new DataSourceTransactionManager(source), true, 10, 90);
        market = new MarketDataService(db, analysis);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(10,'STOCK','NASDAQ','TEST','Test Corp','USD','ACTIVE')");
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
    void backfillPersistsCheckpointAndSkipsDuplicateQuotesOnReplay() {
        sync.queueBackfill(10L);
        var job = db.queryForMap("SELECT start_date FROM market_data_job WHERE product_id=10 AND job_type='BACKFILL'");
        LocalDate first = LocalDate.parse(job.get("start_date").toString());
        when(analysis.marketDailyQuotes(any(), any(), any(), eq("NONE")))
                .thenReturn(Map.of("provider", "TEST", "adapterVersion", "1",
                        "records", List.of(quote(first.plusDays(1)))));
        sync.processNext();
        sync.processNext();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM product_daily_quote WHERE product_id=10", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=10", String.class)).isNotNull();
    }

    @Test
    void dailyUpdateStartsAfterLastStoredDateAndDoesNotRequeueSameTarget() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        db.update("UPDATE investment_product SET history_end_date=? WHERE id=10", target.minusDays(1));
        when(analysis.marketDailyQuotes(any(), eq(target), eq(target), eq("NONE")))
                .thenReturn(Map.of("provider", "TEST", "adapterVersion", "1", "records", List.of(quote(target))));
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isEqualTo(1);
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("SUCCEEDED");
        verify(analysis).marketDailyQuotes(any(), eq(target), eq(target), eq("NONE"));
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isZero();
    }

    @Test
    void dailyUpdateWaitsForBackfillToFinish() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        sync.queueBackfill(10L);
        db.update("UPDATE investment_product SET history_end_date=? WHERE id=10", target.minusDays(2));
        assertThat(sync.queueDaily(java.util.Set.of("NASDAQ"), target)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE product_id=10 "
                + "AND job_type='DAILY_UPDATE'", Integer.class)).isZero();
    }

    @Test
    void providerFailureWaitsThenRetriesWithoutAdvancingCheckpoint() {
        LocalDate target = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        db.update("UPDATE investment_product SET history_end_date=? WHERE id=10", target.minusDays(1));
        sync.queueDaily(java.util.Set.of("NASDAQ"), target);
        when(analysis.marketDailyQuotes(any(), any(), any(), eq("NONE")))
                .thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(Map.of("provider", "TEST", "adapterVersion", "1", "records", List.of(quote(target))));
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("RETRY_WAIT");
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=10", String.class)).isNull();
        db.update("UPDATE market_data_job SET next_retry_at=? WHERE product_id=10", "2000-01-01T00:00:00");
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("SUCCEEDED");
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
