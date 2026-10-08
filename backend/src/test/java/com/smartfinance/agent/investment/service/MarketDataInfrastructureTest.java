package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Market Data 的职责边界：只做目录 → 产品落库 → 批量调度原有历史行情能力。
 */
class MarketDataInfrastructureTest {
    private Path file;
    private JdbcTemplate db;
    private MarketDataSyncService sync;
    private InvestmentProductMapper products;
    private AnalysisServiceClient analysis;
    private InvestmentHistoryPreparationService historyPreparation;
    private InvestmentDataJobService tasks;

    @BeforeEach
    void setup() throws Exception {
        file = Files.createTempFile("market-data-", ".db");
        var source = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath(), "", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db = new JdbcTemplate(source);
        products = mock(InvestmentProductMapper.class);
        analysis = mock(AnalysisServiceClient.class);
        historyPreparation = mock(InvestmentHistoryPreparationService.class);
        tasks = mock(InvestmentDataJobService.class);
        when(historyPreparation.incrementalStart(any())).thenAnswer(call -> {
            InvestmentProduct product = call.getArgument(0);
            return product.getHistoryEndDate() == null ? null : product.getHistoryEndDate().plusDays(1);
        });
        when(historyPreparation.prepareProduct(any(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(call -> {
                    InvestmentProduct product = call.getArgument(0);
                    LocalDate start = call.getArgument(3);
                    LocalDate end = call.getArgument(4);
                    product.setHistoryEndDate(end);
                    return new InvestmentHistoryPreparationService.PreparationResult(
                            10, start, start, end, true, "dataset-v1", false);
                });
        when(historyPreparation.prepareDemandProduct(any(),anyString(),anyBoolean(),any(),any()))
                .thenAnswer(call->{
                    InvestmentProduct product=call.getArgument(0);LocalDate start=call.getArgument(3),end=call.getArgument(4);
                    product.setHistoryEndDate(end);
                    return new InvestmentHistoryPreparationService.PreparationResult(10,start,start,end,false,"dataset-v1",false,end);
                });
        sync = new MarketDataSyncService(db, products, analysis, historyPreparation, true, 10,
                365, 600, 6, tasks, new DataSourceTransactionManager(source));
        sync.setFullLibraryEnabled(true);
    }

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(file);
    }

    @Test
    void quoteSummaryKeepsMissingProductsAndUsesBoundedQueries() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency) VALUES(91,'STOCK','SSE','TEST','stock','CNY')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) VALUES "
                + "(91,'2026-01-05','NONE',10,'TEST'),(91,'2026-01-06','NONE',11,'TEST'),(91,'2026-01-04','QFQ',5,'TEST')");
        var observed=spy(db);
        var reader=new MarketDataService(observed,analysis,mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        var ids=java.util.stream.LongStream.rangeClosed(1,501).boxed().toList();
        var summaries=reader.getQuoteSummaries(ids);
        assertThat(summaries).hasSize(501);
        assertThat(summaries.get(91L)).containsEntry("history_start_date","2026-01-05").containsEntry("history_end_date","2026-01-06");
        assertThat(((Number)summaries.get(91L).get("observations")).longValue()).isEqualTo(2);
        assertThat(summaries.get(501L)).containsEntry("history_start_date",null).containsEntry("history_end_date",null).containsEntry("observations",0L);
        verify(observed,times(2)).queryForList(org.mockito.ArgumentMatchers.contains("MIN(trade_date)"),any(Object[].class));
        org.mockito.Mockito.verifyNoInteractions(analysis);
    }

    @Test
    void commonDatesRespectPassedPriceBasisAndValidFundReturns() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency) VALUES "
                + "(91,'STOCK','SSE','STOCK','stock','CNY'),(92,'ETF','SSE','ETF','etf','CNY'),(93,'MUTUAL_FUND','FUND_CN','FUND','fund','CNY')");
        for(int day=5;day<=9;day++) {
            String date=LocalDate.of(2026,1,day).toString();
            for(long id:List.of(91L,92L,93L))db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,total_return_index,source) VALUES(?,?,'NONE',10,?,'TEST')",
                    id,date,id==93 && day!=8?new java.math.BigDecimal("12"):null);
            if(day!=6)db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) VALUES(91,?,'QFQ',?,'TEST')",date,day==7?0:5);
        }
        var reader=new MarketDataService(db,analysis,mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        var basis=Map.of(91L,"QFQ",92L,"NONE",93L,"NONE");
        assertThat(reader.commonObservedDates(basis,java.util.Set.of(93L),LocalDate.of(2026,1,8)))
                .containsExactly(LocalDate.of(2026,1,5));
        for(var invalid:List.of(Map.<Long,String>of(),Map.of(0L,"NONE"),Map.of(91L,"BAD")))
            org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.commonObservedDates(invalid,java.util.Set.of(),LocalDate.of(2026,1,8)))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->reader.commonObservedDates(basis,java.util.Set.of(999L),LocalDate.of(2026,1,8)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(analysis);
    }

    @Test
    void overviewCountsSyncedProductsOnceAcrossDaysAndAdjustments() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(91,'STOCK','SSE','600091','股票','CNY','ACTIVE'),"
                + "(92,'STOCK','SSE','600092','尚无数据','CNY','ACTIVE'),"
                + "(93,'ETF','SSE','510093','基金','CNY','ACTIVE')");
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay();
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source,synced_at) "
                + "VALUES(91,'2026-01-05','NONE',10,'TEST',?),"
                + "(91,'2026-01-06','NONE',11,'TEST',?),"
                + "(91,'2026-01-05','QFQ',9,'TEST',?),"
                + "(91,'2026-01-04','QFQ',8,'TEST',?),"
                + "(93,'2026-01-05','NONE',1,'TEST',?),"
                + "(93,'2026-01-06','NONE',2,'TEST',?)",
                today, today.plusHours(12), today, today, today.minusSeconds(1), today.plusDays(1));

        var overview = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class)).overview();

        var stocks = overview.stream().filter(row -> "STOCK".equals(row.get("productType"))).findFirst().orElseThrow();
        var etfs = overview.stream().filter(row -> "ETF".equals(row.get("productType"))).findFirst().orElseThrow();
        assertThat(((Number) stocks.get("productCount")).longValue()).isEqualTo(2);
        assertThat(stocks).containsEntry("todaySynced", 1L)
                .containsEntry("historyStartDate", "2026-01-05")
                .containsEntry("latestDataDate", "2026-01-06");
        assertThat(etfs).containsEntry("todaySynced", 0L);
    }

    @Test
    void usCoverageUsesTheProductCalendarAndReportsMissingTradingDates() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) VALUES(199,'STOCK','NASDAQ','NVDA','test','USD','ACTIVE')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) VALUES(199,'2026-07-20','NONE',10,'TEST'),(199,'2026-07-21','NONE',0,'TEST'),(199,'2026-07-22','NONE',11,'TEST')");
        when(analysis.quoteAvailability(any(), eq(LocalDate.of(2026,7,20)), eq(LocalDate.of(2026,7,22)), any())).thenReturn(Map.of(
                "calendarKnown",true,"expectedThroughDate","2026-07-22","targetDate","2026-07-22",
                "expectedDates",List.of("2026-07-20","2026-07-21","2026-07-22"),"ruleProfile","US_CLOSE","ruleVersion","quote-availability-v1"));
        var coverage = new MarketDataService(db,analysis,mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class)).coverage(199L,"NONE");
        assertThat(coverage).containsEntry("coverageStatus","GAPS").containsEntry("missingTradingDays",1L).containsEntry("coverageRatio",2.0/3);
        var product = org.mockito.ArgumentCaptor.forClass(InvestmentProduct.class);
        verify(analysis).quoteAvailability(product.capture(),eq(LocalDate.of(2026,7,20)),eq(LocalDate.of(2026,7,22)),any());
        assertThat(product.getValue().getMarket()).isEqualTo("NASDAQ");
        assertThat(product.getValue().getProductType()).isEqualTo("STOCK");
        verify(analysis,never()).aShareTradingDates(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void unknownCalendarOrUnpublishedTailCannotClaimCompleteCoverage() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,fund_category) VALUES(198,'MUTUAL_FUND','FUND_CN','QDII','test','CNY','ACTIVE','QDII_INDEX_FUND')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) VALUES(198,'2026-07-20','NONE',1,'TEST'),(198,'2026-07-21','NONE',2,'TEST')");
        var reader = new MarketDataService(db,analysis,mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        when(analysis.quoteAvailability(any(),any(),any(),any())).thenReturn(Map.of("calendarKnown",false,"expectedDates",List.of(),"targetDate","2026-07-20","ruleProfile","QDII_NAV","ruleVersion","quote-availability-v1"));
        assertThat(reader.coverage(198L,"NONE")).containsEntry("coverageStatus","CALENDAR_UNAVAILABLE").containsEntry("coverageRatio",null);
        when(analysis.quoteAvailability(any(),any(),any(),any())).thenReturn(Map.of("calendarKnown",true,"expectedThroughDate","2026-07-20","expectedDates",List.of("2026-07-20"),"targetDate","2026-07-20","ruleProfile","CN_NAV","ruleVersion","quote-availability-v1"));
        assertThat(reader.coverage(198L,"NONE")).containsEntry("coverageStatus","CALENDAR_UNAVAILABLE").containsEntry("coverageRatio",null);
    }

    @Test
    void singleObservedQuoteDoesNotClaimFullHistoryCoverage() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(99,'STOCK','SSE','600000','测试','CNY','ACTIVE')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) "
                + "VALUES(99,'2026-07-17','NONE',10,'TEST')");
        when(analysis.quoteAvailability(any(),any(),any(),any())).thenReturn(Map.of("calendarKnown",true,
                "expectedThroughDate","2026-07-17","targetDate","2026-07-17","expectedDates",List.of("2026-07-17"),
                "ruleProfile","CN_CLOSE","ruleVersion","quote-availability-v1"));

        Map<String, Object> coverage = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class)).coverage(99L, "NONE");

        assertThat(coverage).containsEntry("coverageScope", "OBSERVED_RANGE")
                .containsEntry("coverageStatus", "SAMPLE_COMPLETE")
                .containsEntry("historyCoverageComplete", false);
    }

    @Test
    void combinedAssetSearchIncludesStocksAndFundsButNotIndexesOrEtfs() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) VALUES "
                + "(991,'STOCK','NASDAQ','NVDA','NVIDIA Corporation','USD','ACTIVE'),"
                + "(992,'MUTUAL_FUND','FUND_CN','010736','易方达基金','CNY','ACTIVE'),"
                + "(993,'INDEX','US_INDEX','NDX','纳斯达克','USD','ACTIVE'),"
                + "(994,'ETF','NYSE','SPY','SPDR ETF','USD','ACTIVE')");
        var result = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class)).products(null, null, null, "STOCK,FUND", "ACTIVE", 1, 25);
        assertThat(((Number) result.get("total")).longValue()).isEqualTo(2);
        assertThat((List<Map<String,Object>>) result.get("items"))
                .extracting(row -> row.get("code")).containsExactlyInAnyOrder("NVDA", "010736");
    }

    @Test
    void sourceChineseAliasesFindOfficialUsProductWithoutReplacingNameOrCreatingSecurities() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(991,'STOCK','NASDAQ','NVDA','NVIDIA Corporation','USD','ACTIVE')");
        when(analysis.marketProductNames("英伟达")).thenReturn(List.of(
                Map.of("code", "NVDA", "aliases", List.of("英伟达", "NVIDIA")),
                Map.of("code", "MISSING", "aliases", List.of("虚构证券"))));
        var names = new InvestmentProductNameService(db, analysis, new com.fasterxml.jackson.databind.ObjectMapper());
        var service = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        service.setProductNames(names);

        var result = service.products("英伟达", null, null, "STOCK,FUND", "ACTIVE", 1, 25);
        service.products("英伟达", null, null, "STOCK,FUND", "ACTIVE", 1, 25);

        assertThat(((Number)result.get("total")).longValue()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT name FROM investment_product WHERE id=991", String.class)).isEqualTo("NVIDIA Corporation");
        assertThat(db.queryForObject("SELECT name_aliases FROM investment_product WHERE id=991", String.class)).contains("英伟达");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product WHERE code='MISSING'", Integer.class)).isZero();
        verify(analysis, times(1)).marketProductNames("英伟达");
    }

    @Test
    void cachedSourceNamesCanBeAppliedAfterCatalogInitialization() {
        when(analysis.marketProductNames("英伟达")).thenReturn(List.of(
                Map.of("code", "NVDA", "aliases", List.of("英伟达"))));
        var service = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        service.setProductNames(new InvestmentProductNameService(db, analysis, new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThat(service.products("英伟达", null, null, "STOCK,FUND", "ACTIVE", 1, 25).get("total")).isEqualTo(0L);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(991,'STOCK','NASDAQ','NVDA','NVIDIA Corporation','USD','ACTIVE')");
        assertThat(service.products("英伟达", null, null, "STOCK,FUND", "ACTIVE", 1, 25).get("total")).isEqualTo(1L);
        verify(analysis, times(1)).marketProductNames("英伟达");
    }

    @Test
    void optionalNameProviderFailureKeepsLocalSearchAndDoesNotAffectChineseStockOnlySearch() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(991,'STOCK','SSE','600001','测试股票','CNY','ACTIVE')");
        when(analysis.marketProductNames("测试")).thenThrow(new IllegalStateException("provider unavailable"));
        var service = new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        service.setProductNames(new InvestmentProductNameService(db, analysis, new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThat(service.products("测试", null, null, "STOCK,FUND", "ACTIVE", 1, 25).get("total")).isEqualTo(1L);
        assertThat(service.products("测试", null, null, "STOCK,FUND", "ACTIVE", 1, 25).get("total")).isEqualTo(1L);
        service.products("股票", null, "CN_A", "STOCK", "ACTIVE", 1, 25);
        verify(analysis, times(1)).marketProductNames("测试");
        verify(analysis, never()).marketProductNames("股票");
    }

    @Test
    void slimMigrationDropsScopeAndCoverageState() {
        List<String> tables = db.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).doesNotContain("product_quote_coverage", "market_data_scope_product");
        List<String> jobColumns = db.queryForList("PRAGMA table_info(investment_data_job)").stream()
                .map(row -> String.valueOf(row.get("name"))).toList();
        assertThat(jobColumns).doesNotContain("coverage_complete", "sample_start_date", "dataset_version");
    }

    @Test
    void catalogImportWritesProductsIntoInvestmentProduct() {
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY"),
                catalogEntry("STOCK", "SZSE", "000001", "平安银行", "CNY")));
        when(products.selectList(any())).thenReturn(List.of());

        int inserted = sync.importCatalog("CN_A");

        assertThat(inserted).isEqualTo(2);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM investment_product WHERE source_metadata='AKSHARE_A_LIST'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void initialCatalogBackfillUsesThePublicationBound() {
        var availability = mock(InvestmentQuoteAvailabilityService.class);
        var published = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(7);
        when(availability.target(any(), any(), any())).thenReturn(published);
        sync.setAvailability(availability);
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "测试", "CNY")));

        sync.importCatalog("CN_A");

        assertThat(db.queryForObject("SELECT target_date FROM market_data_job", String.class))
                .isEqualTo(published.toString());
    }

    @Test
    void explicitFullRepairUsesThePublicationBound() {
        var availability = mock(InvestmentQuoteAvailabilityService.class);
        var published = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(7);
        when(availability.target(any(), any(), any())).thenReturn(published);
        sync.setAvailability(availability);
        workerProduct(94L, "MUTUAL_FUND", "FUND_CN");

        sync.queueBackfill(94L);

        assertThat(db.queryForObject("SELECT target_date FROM market_data_job WHERE product_id=94", String.class))
                .isEqualTo(published.toString());
    }

    @Test
    void defaultCatalogDoesNotCreateFullMarketHistoryOrResearchJobs() {
        sync.setFullLibraryEnabled(false);
        sync.setRequirements(new MarketDataRequirementService(db));
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(catalogEntry("STOCK","SSE","600000","test","CNY")));
        sync.importCatalog("CN_A");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job",Integer.class)).isZero();
    }

    @Test
    void legacyFullMarketQueueIsNotConsumedWithoutActiveDemand() {
        sync.setFullLibraryEnabled(false);
        sync.setRequirements(new MarketDataRequirementService(db));
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,catalog_market) VALUES(901,'STOCK','SSE','600901','test','CNY','ACTIVE','CN_A')");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,updated_at) VALUES(901,'BACKFILL','QUEUED','1976-01-01','2026-09-30','2026-01-01')");
        sync.processNext();
        verify(historyPreparation,never()).prepareProduct(any(),anyString(),anyBoolean(),any(),any());
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=901",String.class)).isEqualTo("QUEUED");
    }

    @Test
    void legacyLargeWindowIsClampedToCurrentDetailDemand() {
        sync.setFullLibraryEnabled(false);
        var requirements=new MarketDataRequirementService(db);
        sync.setRequirements(requirements);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,catalog_market) VALUES(902,'STOCK','SSE','600902','test','CNY','ACTIVE','CN_A')");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,updated_at) VALUES(902,'BACKFILL','QUEUED','1976-01-01','2026-09-30','2026-01-01')");
        requirements.save(1,"DETAIL","902",902,"PRICE","REQUIRED",LocalDate.of(2026,9,1),LocalDate.of(2026,9,3),false,requirements.now().plusHours(1));
        var product=new InvestmentProduct();product.setId(902L);product.setProductType("STOCK");product.setMarket("SSE");product.setCode("600902");
        when(products.selectById(902L)).thenReturn(product);
        sync.processNext();
        verify(historyPreparation).prepareDemandProduct(eq(product),anyString(),anyBoolean(),eq(LocalDate.of(2026,9,1)),eq(LocalDate.of(2026,9,3)));
    }

    @Test
    void successfulJobsWithNullErrorsCanQueueNewDemandWithoutBlockingLaterProducts() {
        var requirements = new MarketDataRequirementService(db);
        sync.setRequirements(requirements);
        var end = requirements.now().toLocalDate().minusDays(2);
        var start = end.minusDays(10);
        var markets = Map.of(991L, "NASDAQ", 992L, "NYSE", 993L, "FUND_CN");
        for (long id : List.of(991L, 992L, 993L)) {
            workerProduct(id, id == 993L ? "MUTUAL_FUND" : "STOCK", markets.get(id));
            requirements.save(1, "DETAIL", String.valueOf(id), id, "PRICE", "REQUIRED",
                    start, end, false, requirements.now().plusHours(1));
            db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,last_error,updated_at) "
                    + "VALUES(?,'BACKFILL','SUCCEEDED',?,?,?,NULL,?)", id, start.minusDays(1), end.minusDays(1), end.minusDays(1), requirements.now());
        }

        assertThat(sync.reconcileDemand()).isEqualTo(3);

        for (long id : List.of(991L, 992L, 993L)) {
            assertThat(db.queryForMap("SELECT status,start_date,target_date,checkpoint_date,last_error FROM market_data_job WHERE product_id=?", id))
                    .containsEntry("status", "QUEUED").containsEntry("start_date", start.toString())
                    .containsEntry("target_date", end.toString()).containsEntry("checkpoint_date", null).containsEntry("last_error", null);
        }
    }

    @Test
    void noNewDataAndUnverifiedWindowKeepCooldownButExplicitRepairCanRetry() {
        var requirements = new MarketDataRequirementService(db);
        sync.setRequirements(requirements);
        var end = requirements.now().toLocalDate().minusDays(2);
        var start = end.minusDays(10);
        var errors = Map.of(994L, "NO_NEW_DATA", 995L, "UNVERIFIED_WINDOW");
        for (long id : List.of(994L, 995L)) {
            workerProduct(id, "STOCK", "NASDAQ");
            db.update("UPDATE investment_product SET code=? WHERE id=?", "TEST" + id, id);
            requirements.save(1, "DETAIL", String.valueOf(id), id, "PRICE", "REQUIRED",
                    start, end, false, requirements.now().plusHours(1));
            db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,last_error,updated_at) "
                    + "VALUES(?,'BACKFILL','SUCCEEDED',?,?,?,?)", id, start, end, errors.get(id), requirements.now());
        }

        assertThat(sync.reconcileDemand()).isZero();
        for (long id : List.of(994L, 995L)) {
            assertThat(db.queryForMap("SELECT status,last_error FROM market_data_job WHERE product_id=?", id))
                    .containsEntry("status", "SUCCEEDED").containsEntry("last_error", errors.get(id));
            sync.queueBackfill(id);
            assertThat(db.queryForMap("SELECT status,last_error FROM market_data_job WHERE product_id=?", id))
                    .containsEntry("status", "QUEUED").containsEntry("last_error", null);
        }
    }

    @Test
    void unverifiedHistoryDoesNotAcknowledgeDemandOrSpinOnTheSameChunk() {
        sync.setFullLibraryEnabled(false);var requirements=new MarketDataRequirementService(db);sync.setRequirements(requirements);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,catalog_market) VALUES(903,'STOCK','SSE','600903','test','CNY','ACTIVE','CN_A')");
        requirements.save(1,"DETAIL","903",903,"PRICE","REQUIRED",LocalDate.of(2020,1,1),LocalDate.now(),false,requirements.now().plusHours(1));
        var product=new InvestmentProduct();product.setId(903L);product.setProductType("STOCK");product.setMarket("SSE");product.setCode("600903");
        when(products.selectById(903L)).thenReturn(product);
        doAnswer(call->{product.setHistoryEndDate(call.getArgument(4));return new InvestmentHistoryPreparationService.PreparationResult(1,call.getArgument(3),call.getArgument(4),call.getArgument(4),false,null,false,null);})
                .when(historyPreparation).prepareDemandProduct(any(),anyString(),anyBoolean(),any(),any());
        sync.reconcileDemand();sync.processNext();sync.reconcileDemand();sync.processNext();
        verify(historyPreparation,times(1)).prepareDemandProduct(any(),anyString(),anyBoolean(),any(),any());
        assertThat(requirements.active().get(0).get("prepared_end_date")).isNull();
        assertThat(db.queryForObject("SELECT last_error FROM market_data_job WHERE product_id=903",String.class)).isEqualTo("UNVERIFIED_WINDOW");
    }
    @Test
    void visibleFundDemandRunsBeforeOlderFullLibraryWorkAndBulkKeepsAQuota() {
        var requirements=new MarketDataRequirementService(db);sync.setRequirements(requirements);
        org.springframework.test.util.ReflectionTestUtils.setField(sync,"foregroundQuota",1);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,catalog_market) VALUES(904,'STOCK','SSE','600904','bulk','CNY','ACTIVE','CN_A'),(905,'MUTUAL_FUND','FUND_CN','000905','visible','CNY','ACTIVE','FUND_CN')");
        LocalDate start=LocalDate.now().minusDays(3),end=LocalDate.now();
        requirements.save(1,"DETAIL","905",905,"PRICE","REQUIRED",start,end,false,requirements.now().plusHours(1));
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,updated_at) VALUES(904,'BACKFILL','QUEUED',?,?,'2020-01-01'),(905,'BACKFILL','QUEUED',?,?,'2026-10-01')",start,end,start,end);
        for(long id:List.of(904L,905L)) {var product=new InvestmentProduct();product.setId(id);product.setProductType(id==905?"MUTUAL_FUND":"STOCK");product.setMarket(id==905?"FUND_CN":"SSE");product.setCode("test");when(products.selectById(id)).thenReturn(product);}
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=904",String.class)).isEqualTo("QUEUED");
        db.update("UPDATE market_data_job SET status='QUEUED',checkpoint_date=NULL WHERE product_id=905");
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=904",String.class)).isEqualTo("SUCCEEDED");
    }
    @Test
    void monitorSeparatesInactiveBacklogFromActualDemand() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) VALUES(906,'STOCK','SSE','600906','idle','CNY','ACTIVE'),(907,'STOCK','SSE','600907','used','CNY','ACTIVE')");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,updated_at) VALUES(906,'BACKFILL','QUEUED','2020-01-01','2026-10-01','2026-10-01'),(907,'BACKFILL','QUEUED','2020-01-01','2026-10-01','2026-10-01')");
        var requirements=new MarketDataRequirementService(db);
        requirements.save(1,"DETAIL","907",907,"PRICE","REQUIRED",LocalDate.now().minusDays(10),LocalDate.now(),false,requirements.now().plusHours(1));
        var market=new MarketDataService(db, analysis, mock(com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper.class));
        var group=market.overview().get(0);
        assertThat(group).containsEntry("activeJobs",1L).containsEntry("pausedJobs",1L);
        assertThat(market.jobs().stream().filter(row->"600906".equals(row.get("code"))).findFirst().orElseThrow()).containsEntry("scopeState","PAUSED_BY_SCOPE");
    }
    @Test
    void fullLibraryKeepsItsCheckpointWhenForegroundDemandBorrowsTheSameJob() {
        var requirements=new MarketDataRequirementService(db);sync.setRequirements(requirements);
        LocalDate end=LocalDate.now(),start=end.minusDays(3);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,catalog_market) VALUES(908,'STOCK','SSE','600908','test','CNY','ACTIVE','CN_A')");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,updated_at) VALUES(908,'BACKFILL','QUEUED','1976-01-01',?,'2020-01-01','2020-01-01')",end);
        requirements.save(1,"DETAIL","908",908,"PRICE","REQUIRED",start,end,false,requirements.now().plusHours(1));
        var product=new InvestmentProduct();product.setId(908L);product.setProductType("STOCK");product.setMarket("SSE");product.setCode("600908");when(products.selectById(908L)).thenReturn(product);
        sync.processNext();
        verify(historyPreparation).prepareDemandProduct(eq(product),anyString(),anyBoolean(),eq(start),eq(end));
        var queued=db.queryForMap("SELECT * FROM market_data_job WHERE product_id=908");
        assertThat(queued).containsEntry("status","QUEUED").containsEntry("start_date","1976-01-01").containsEntry("checkpoint_date","2020-01-01");
        assertThat(requirements.active().get(0).get("prepared_end_date")).isEqualTo(end.toString());
    }

    @Test
    void catalogImportQueuesBackfillOnlyForProductsWithoutLocalHistory() {
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY"),
                catalogEntry("STOCK", "SZSE", "000001", "平安银行", "CNY")));
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,"
                        + "source_metadata,history_end_date) VALUES(7,'STOCK','SSE','600000','已有历史','CNY',"
                        + "'ACTIVE','AKSHARE_A_LIST','2026-01-05')");
        InvestmentProduct existing = new InvestmentProduct();
        existing.setId(7L);
        existing.setProductType("STOCK");
        existing.setMarket("SSE");
        existing.setCode("600000");
        when(products.selectList(any())).thenReturn(List.of(existing));

        sync.importCatalog("CN_A");

        // 已有本地历史的产品不会被再次全量回补。
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM market_data_job WHERE product_id=7", Integer.class)).isZero();
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM market_data_job j JOIN investment_product p ON p.id=j.product_id "
                        + "WHERE j.job_type='BACKFILL'", Integer.class)).isEqualTo(1);
    }

    @Test
    void dailyUpdateStartsAfterTheLastStoredDate() {
        LocalDate target = LocalDate.of(2026, 3, 10);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,"
                        + "source_metadata,history_end_date) VALUES(11,'STOCK','SSE','600000','浦发银行','CNY',"
                        + "'ACTIVE','AKSHARE_A_LIST','2026-03-06')");

        int queued = sync.queueDaily(java.util.Set.of("SSE"), target);

        assertThat(queued).isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT start_date FROM market_data_job WHERE product_id=11 AND job_type='DAILY_UPDATE'",
                String.class)).isEqualTo("2026-03-07");
    }

    @Test
    void dailyUpdateIsSkippedWhenNothingNewHasBeenPublished() {
        LocalDate target = LocalDate.of(2026, 3, 10);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,"
                        + "source_metadata,history_end_date) VALUES(12,'STOCK','SSE','600001','测试','CNY',"
                        + "'ACTIVE','AKSHARE_A_LIST','2026-03-10')");

        assertThat(sync.queueDaily(java.util.Set.of("SSE"), target)).isZero();
    }

    @Test
    void marketWorkerReusesTheSameHistoryCapabilityAsPersonalHoldings() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,"
                        + "source_metadata) VALUES(10,'STOCK','SSE','600000','浦发银行','CNY','ACTIVE','AKSHARE_A_LIST')");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                        + "attempt_count,updated_at) VALUES(10,'BACKFILL','QUEUED','2016-01-01','2026-01-01',0,"
                        + "CURRENT_TIMESTAMP)");
        InvestmentProduct product = new InvestmentProduct();
        product.setId(10L);
        product.setProductType("STOCK");
        product.setMarket("SSE");
        product.setListingDate(LocalDate.of(2016, 1, 1));
        when(products.selectById(10L)).thenReturn(product);

        sync.processNext();

        // 走的是 product-level 的历史行情能力，不再有第二套摄取/校验/落库。
        verify(historyPreparation).prepareProduct(eq(product), eq("STOCK_HISTORY"), eq(true),
                eq(LocalDate.of(2016, 1, 1)), eq(LocalDate.of(2016, 12, 30)));
        assertThat(db.queryForObject(
                "SELECT status FROM market_data_job WHERE product_id=10", String.class)).isEqualTo("QUEUED");
        assertThat(db.queryForObject(
                "SELECT checkpoint_date FROM market_data_job WHERE product_id=10", String.class))
                .isEqualTo("2016-12-30");
        verify(tasks).queueAnalysisForProduct(10L);
    }

    @Test
    void catalogMembershipSurvivesProviderChangesAndMetadataRefresh() {
        var first = Map.<String, Object>of("productType", "STOCK", "market", "NASDAQ", "code", "AAPL",
                "name", "Apple", "currency", "USD", "source", "AKSHARE_US_SPOT");
        when(analysis.marketCatalog("US")).thenReturn(List.of(first));
        assertThat(sync.importCatalog("US")).isEqualTo(1);
        var renamed = new java.util.HashMap<>(first);
        renamed.put("name", "Apple Inc.");
        renamed.put("source", "NEW_PROVIDER");
        when(analysis.marketCatalog("US")).thenReturn(List.of(renamed));
        assertThat(sync.importCatalog("US")).isZero();
        assertThat(db.queryForMap("SELECT catalog_market,name,source_metadata FROM investment_product"))
                .containsEntry("catalog_market", "US").containsEntry("name", "Apple Inc.")
                .containsEntry("source_metadata", "NEW_PROVIDER");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCatalogImportsKeepOneProductAndOneBackfillPerIdentity() throws Exception {
        var concurrentDb = spy(db);
        var snapshotsRead = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            snapshotsRead.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return snapshot;
        }).when(concurrentDb).queryForList("SELECT product_type,market,code FROM investment_product");
        var concurrentSync = new MarketDataSyncService(concurrentDb, products, analysis, historyPreparation, true, 10);
        concurrentSync.setFullLibraryEnabled(true);
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY")));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentSync.importCatalog("CN_A"));
            var second = executor.submit(() -> concurrentSync.importCatalog("CN_A"));
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT status FROM market_catalog_state WHERE market='CN_A'", String.class))
                    .isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fundCatalogIsImportedAndQueued() {
        when(analysis.marketCatalog("FUND_CN")).thenReturn(List.of(
                catalogEntry("MUTUAL_FUND", "FUND_CN", "000001", "基金", "CNY")));
        sync.importCatalog("FUND_CN");
        assertThat(db.queryForObject("SELECT catalog_market FROM investment_product", String.class))
                .isEqualTo("FUND_CN");
        assertThat(db.queryForObject("SELECT job_type FROM market_data_job", String.class)).isEqualTo("BACKFILL");
    }

    @Test
    void catalogDoesNotRemoveProductsMissingFromOneSnapshot() {
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY"),
                catalogEntry("STOCK", "SZSE", "000001", "平安银行", "CNY")));
        sync.importCatalog("CN_A");
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY")));
        sync.importCatalog("CN_A");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product WHERE status='ACTIVE'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void resumedBackfillUsesCheckpointAndDoesNotForceRefreshAgain() {
        workerProduct(21L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,"
                + "attempt_count,updated_at) VALUES(21,'BACKFILL','QUEUED','2025-01-01','2026-02-01',"
                + "'2025-12-31',0,CURRENT_TIMESTAMP)");
        sync.processNext();
        verify(historyPreparation).prepareProduct(any(), eq("STOCK_HISTORY"), eq(false),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 2, 1)));
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=21", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void failedWindowKeepsCheckpointForRetry() {
        workerProduct(22L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,"
                + "attempt_count,updated_at) VALUES(22,'BACKFILL','QUEUED','2025-01-01','2026-02-01',"
                + "'2025-12-31',0,CURRENT_TIMESTAMP)");
        doThrow(new IllegalStateException("provider unavailable")).when(historyPreparation)
                .prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date,attempt_count FROM market_data_job WHERE product_id=22"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("checkpoint_date", "2025-12-31")
                .containsEntry("attempt_count", 1);
        verify(tasks, never()).queueAnalysisForProduct(any());
    }

    @Test
    void defaultBackfillUsesLargerWindowsAndRetriesBeforeAdvancingCheckpoint() {
        workerProduct(81L, "STOCK", "SSE");
        InvestmentProduct product = products.selectById(81L);
        product.setListingDate(LocalDate.of(2016, 1, 1));
        MarketDataSyncService defaults = new MarketDataSyncService(db, products, analysis,
                historyPreparation, true, 50);
        defaults.setFullLibraryEnabled(true);
        doThrow(new IllegalStateException("provider timeout"))
                .doAnswer(call -> {
                    LocalDate start = call.getArgument(3), end = call.getArgument(4);
                    product.setHistoryEndDate(end);
                    return new InvestmentHistoryPreparationService.PreparationResult(
                            2400, start, start, end, true, "dataset-v1", false);
                }).when(historyPreparation).prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(81,'BACKFILL','QUEUED','2016-01-01','2026-10-01',0,CURRENT_TIMESTAMP)");

        defaults.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date FROM market_data_job WHERE product_id=81"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("checkpoint_date", null);
        db.update("UPDATE market_data_job SET next_retry_at='2000-01-01 00:00:00' WHERE product_id=81");
        defaults.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date FROM market_data_job WHERE product_id=81"))
                .containsEntry("status", "QUEUED").containsEntry("checkpoint_date", "2025-12-28");
        defaults.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date FROM market_data_job WHERE product_id=81"))
                .containsEntry("status", "SUCCEEDED").containsEntry("checkpoint_date", "2026-10-01");
        verify(historyPreparation, times(2)).prepareProduct(any(), eq("STOCK_HISTORY"), eq(true),
                eq(LocalDate.of(2016, 1, 1)), eq(LocalDate.of(2025, 12, 28)));
        verify(historyPreparation).prepareProduct(any(), eq("STOCK_HISTORY"), eq(false),
                eq(LocalDate.of(2025, 12, 29)), eq(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void fundBackfillConsumesOneFullProviderResponse() {
        workerProduct(23L, "MUTUAL_FUND", "FUND_CN");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(23,'BACKFILL','QUEUED','2016-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        sync.processNext();
        verify(historyPreparation).prepareProduct(any(), eq("FUND_NAV_HISTORY"), eq(true),
                eq(LocalDate.of(2016, 1, 1)), eq(LocalDate.of(2026, 1, 1)));
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=23", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void unknownListingDateBackfillDoesNotRepeatPreListingWindows() {
        workerProduct(34L, "STOCK", "NASDAQ");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(34,'BACKFILL','QUEUED','1976-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");

        sync.processNext();

        verify(historyPreparation).prepareProduct(any(), eq("STOCK_HISTORY"), eq(true),
                eq(LocalDate.of(1976, 1, 1)), eq(LocalDate.of(2026, 1, 1)));
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=34", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void staleWorkerCannotCompleteReclaimedJob() {
        workerProduct(24L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(24,'DAILY_UPDATE','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        doAnswer(call -> {
                    db.update("UPDATE market_data_job SET lease_token='replacement-owner' WHERE product_id=24");
                    return new InvestmentHistoryPreparationService.PreparationResult(1, LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), false, "v1", false);
                }).when(historyPreparation).prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=24", String.class))
                .isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=24", String.class))
                .isNull();
        verify(tasks, never()).queueAnalysisForProduct(any());
    }

    @Test
    void repeatedLeaseExpirationsStopAfterTheConfiguredAttemptLimit() {
        workerProduct(33L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,lease_until,lease_token,"
                + "attempt_count,updated_at) VALUES(33,'BACKFILL','RUNNING','2026-01-01','2026-01-01',"
                + "'2000-01-01 00:00:00','expired-owner',5,CURRENT_TIMESTAMP)");
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,attempt_count,last_error FROM market_data_job WHERE product_id=33"))
                .containsEntry("status", "FAILED").containsEntry("attempt_count", 6)
                .containsEntry("last_error", "LEASE_EXPIRED");
        verify(historyPreparation, never()).prepareProduct(any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void anotherRunningJobForTheProductPreventsClaim() {
        workerProduct(25L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,lease_until,"
                + "attempt_count,updated_at) VALUES(25,'BACKFILL','RUNNING','2025-01-01','2026-01-01',"
                + "'2099-01-01 00:00:00',0,CURRENT_TIMESTAMP)");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(25,'DAILY_UPDATE','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        sync.processNext();
        verify(historyPreparation, never()).prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE job_type='DAILY_UPDATE'", String.class))
                .isEqualTo("QUEUED");
    }

    @Test
    void missingRequiredPriceSeriesQueuesInitializationDespiteExistingProductEndDate() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status,history_end_date) "
                + "VALUES(26,'STOCK','SSE','600000','测试','CNY','ACTIVE','2026-03-10')");
        doReturn(null).when(historyPreparation).incrementalStart(any());
        assertThat(sync.queueDaily(java.util.Set.of("SSE"), LocalDate.of(2026, 3, 10))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT job_type FROM market_data_job WHERE product_id=26", String.class))
                .isEqualTo("BACKFILL");
    }

    @Test
    void failedBackfillKeepsCheckpointWhenUserRetries() {
        workerProduct(27L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,"
                + "attempt_count,last_error,updated_at) VALUES(27,'BACKFILL','FAILED','2025-01-01','2026-02-01',"
                + "'2025-12-31',6,'provider unavailable',CURRENT_TIMESTAMP)");
        sync.queueBackfill(27L);
        assertThat(db.queryForMap("SELECT status,start_date,checkpoint_date,attempt_count FROM market_data_job WHERE product_id=27"))
                .containsEntry("status", "QUEUED").containsEntry("start_date", "2025-01-01")
                .containsEntry("checkpoint_date", "2025-12-31").containsEntry("attempt_count", 0);
    }

    @Test
    void dailySchedulingDoesNotResetExhaustedBackfillRetriesBeforeCooldownEnds() {
        workerProduct(28L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(28,'BACKFILL','FAILED','2025-01-01','2026-01-01',6,?)",
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        assertThat(sync.queueDaily(java.util.Set.of("SSE"), LocalDate.of(2026, 3, 10))).isZero();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=28", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void emptyFullHistoryIsNotReportedAsSuccessful() {
        workerProduct(29L, "MUTUAL_FUND", "FUND_CN");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(29,'BACKFILL','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        doReturn(new InvestmentHistoryPreparationService.PreparationResult(0, LocalDate.of(2026, 1, 1),
                        null, null, false, null, true)).when(historyPreparation)
                .prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,last_error FROM market_data_job WHERE product_id=29"))
                .containsEntry("status", "FAILED").containsEntry("last_error", "NO_HISTORY_DATA");
        verify(tasks, never()).queueAnalysisForProduct(any());
    }

    @Test
    void incompleteRequiredSeriesDoesNotSucceedBecauseResearchQuotesAlreadyExist() {
        workerProduct(32L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(32,'BACKFILL','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        doReturn(new InvestmentHistoryPreparationService.PreparationResult(10, LocalDate.of(2026, 1, 1),
                        null, null, false, null, true)).when(historyPreparation)
                .prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,last_error,checkpoint_date FROM market_data_job WHERE product_id=32"))
                .containsEntry("status", "FAILED").containsEntry("last_error", "REQUIRED_SERIES_MISSING")
                .containsEntry("checkpoint_date", "2026-01-01");
        sync.queueBackfill(32L);
        assertThat(db.queryForObject("SELECT checkpoint_date FROM market_data_job WHERE product_id=32", String.class)).isNull();
    }

    @Test
    void analysisQueueFailureRetriesEvenWhenHistoryWasAlreadyPersisted() {
        workerProduct(31L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                + "attempt_count,updated_at) VALUES(31,'DAILY_UPDATE','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        doThrow(new IllegalStateException("analysis queue unavailable")).doNothing()
                .when(tasks).queueAnalysisForProduct(31L);
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date,last_error FROM market_data_job WHERE product_id=31"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("checkpoint_date", null)
                .containsEntry("last_error", "ANALYSIS_QUEUE_FAILED");
        db.update("UPDATE market_data_job SET next_retry_at='2000-01-01 00:00:00' WHERE product_id=31");
        doReturn(new InvestmentHistoryPreparationService.PreparationResult(10, LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), false, null, true))
                .when(historyPreparation).prepareProduct(any(), anyString(), anyBoolean(), any(), any());
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=31", String.class))
                .isEqualTo("SUCCEEDED");
        verify(tasks, times(2)).queueAnalysisForProduct(31L);
    }

    @Test
    void bootstrapRecoversDailyUpdatesOnlyForSuccessfulCatalogs() {
        workerProduct(30L, "STOCK", "SSE");
        db.update("UPDATE investment_product SET catalog_market='CN_A',history_end_date='2026-01-01' WHERE id=30");
        db.update("INSERT INTO market_catalog_state(market,status,product_count,last_synced_at) VALUES('CN_A','SUCCEEDED',1,?)",
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        when(analysis.marketCatalog(anyString())).thenReturn(List.of());
        sync.bootstrap();
        assertThat(db.queryForObject("SELECT job_type FROM market_data_job WHERE product_id=30", String.class))
                .isEqualTo("DAILY_UPDATE");
        assertThat(db.queryForObject("SELECT start_date FROM market_data_job WHERE product_id=30", String.class))
                .isEqualTo("2026-01-02");
        verify(analysis, never()).marketCatalog("CN_A");
    }

    @Test
    void bootstrapRefreshesAStaleSuccessfulDirectoryAfterDowntime() {
        db.update("INSERT INTO market_catalog_state(market,status,product_count,last_synced_at) "
                + "VALUES('CN_A','SUCCEEDED',1,'2000-01-01 00:00:00')");
        when(analysis.marketCatalog(anyString())).thenReturn(List.of());
        when(analysis.marketCatalog("CN_A")).thenReturn(List.of(
                catalogEntry("STOCK", "SSE", "600000", "浦发银行", "CNY")));
        sync.bootstrap();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product WHERE catalog_market='CN_A'", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM market_catalog_state WHERE market='CN_A'", String.class))
                .isEqualTo("SUCCEEDED");
        verify(analysis).marketCatalog("CN_A");
    }

    private void workerProduct(long id, String type, String market) {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(?,?,?,'TEST','测试','CNY','ACTIVE')", id, type, market);
        InvestmentProduct product = new InvestmentProduct();
        product.setId(id);
        product.setProductType(type);
        product.setMarket(market);
        when(products.selectById(id)).thenReturn(product);
    }

    @Test
    void exhaustedSourcesResumeAfterCooldownWithoutLosingCheckpoint() {
        workerProduct(60L, "STOCK", "SSE");
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,checkpoint_date,"
                + "attempt_count,last_error,updated_at) VALUES(60,'BACKFILL','FAILED','2024-01-01','2026-01-01',"
                + "'2024-12-31',6,'source unavailable','2000-01-01 00:00:00')");
        sync.queueDaily(java.util.Set.of("SSE"), LocalDate.of(2026, 3, 10));
        assertThat(db.queryForMap("SELECT status,start_date,checkpoint_date,attempt_count FROM market_data_job WHERE product_id=60"))
                .containsEntry("status", "QUEUED").containsEntry("start_date", "2024-01-01")
                .containsEntry("checkpoint_date", "2024-12-31").containsEntry("attempt_count", 0);
    }

    @Test
    void marketRotationLetsFundsRunWhileStockBackfillsRemainQueued() {
        workerProduct(61L, "STOCK", "SSE");
        workerProduct(62L, "STOCK", "SZSE");
        workerProduct(63L, "MUTUAL_FUND", "FUND_CN");
        for (long id : List.of(61L, 62L, 63L)) db.update("INSERT INTO market_data_job(product_id,job_type,status,"
                + "start_date,target_date,attempt_count,updated_at) VALUES(?,'BACKFILL','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)", id);
        sync.processNext();
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=62", String.class)).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=63", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void dailyBacklogDoesNotStarveHistoryOrResearchInTheSameMarket() {
        for (long id : List.of(71L, 72L, 73L, 74L)) {
            workerProduct(id, "MUTUAL_FUND", "FUND_CN");
            db.update("UPDATE investment_product SET code=? WHERE id=?", "FUND" + id, id);
        }
        InvestmentResearchDataService research = mock(InvestmentResearchDataService.class);
        sync.setResearch(research);
        when(research.supports("PROFILE")).thenReturn(true);
        when(research.collect(any(), eq("PROFILE"), any(), any()))
                .thenReturn(Map.of("dataset", "PROFILE", "records", List.of(), "complete", true));
        for (long id : List.of(71L, 72L)) db.update("INSERT INTO market_data_job(product_id,job_type,status,"
                + "start_date,target_date,attempt_count,updated_at) VALUES(?,'DAILY_UPDATE','QUEUED',"
                + "'2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)", id);
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,attempt_count,updated_at) "
                + "VALUES(73,'BACKFILL','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP),"
                + "(74,'PROFILE','QUEUED','2026-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");

        sync.processNext();
        sync.processNext();
        sync.processNext();

        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE job_type='DAILY_UPDATE' "
                + "AND status='SUCCEEDED'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=73", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=74", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void partialResearchSavesRowsAndRetriesSameYearBeforeAdvancing() {
        workerProduct(64L, "MUTUAL_FUND", "FUND_CN");
        InvestmentResearchDataService research = mock(InvestmentResearchDataService.class);
        sync.setResearch(research);
        when(research.supports("FUND_HOLDINGS")).thenReturn(true);
        when(research.yearly("FUND_HOLDINGS")).thenReturn(true);
        Map<String,Object> partial = Map.of("dataset", "FUND_HOLDINGS", "records", List.of(), "complete", false, "failedSections", List.of("bonds"));
        Map<String,Object> complete = Map.of("dataset", "FUND_HOLDINGS", "records", List.of(), "complete", true);
        when(research.collect(any(), eq("FUND_HOLDINGS"), any(), any())).thenReturn(partial, complete, complete);
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,attempt_count,updated_at) "
                + "VALUES(64,'FUND_HOLDINGS','QUEUED','2024-01-01','2025-12-31',0,CURRENT_TIMESTAMP)");
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date FROM market_data_job WHERE product_id=64"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("checkpoint_date", null);
        verify(research).persist(64L, "FUND_HOLDINGS", partial);
        db.update("UPDATE market_data_job SET next_retry_at='2000-01-01 00:00:00' WHERE product_id=64");
        sync.processNext();
        assertThat(db.queryForMap("SELECT status,checkpoint_date FROM market_data_job WHERE product_id=64"))
                .containsEntry("status", "QUEUED").containsEntry("checkpoint_date", "2024-12-31");
        sync.processNext();
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=64", String.class)).isEqualTo("SUCCEEDED");
        verify(research, times(2)).collect(any(), eq("FUND_HOLDINGS"), eq(LocalDate.of(2024,1,1)), eq(LocalDate.of(2024,12,31)));
        verify(research).collect(any(), eq("FUND_HOLDINGS"), eq(LocalDate.of(2025,1,1)), eq(LocalDate.of(2025,12,31)));
    }

    @Test
    void lostResearchLeaseCannotPersistReturnedRows() {
        workerProduct(65L, "STOCK", "SSE");
        InvestmentResearchDataService research = mock(InvestmentResearchDataService.class);
        sync.setResearch(research);
        when(research.supports("FINANCIALS")).thenReturn(true);
        when(research.collect(any(), anyString(), any(), any())).thenAnswer(call -> {
            db.update("UPDATE market_data_job SET lease_token='replacement-owner' WHERE product_id=65");
            return Map.of("complete", true, "records", List.of());
        });
        db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,attempt_count,updated_at) "
                + "VALUES(65,'FINANCIALS','QUEUED','2024-01-01','2026-01-01',0,CURRENT_TIMESTAMP)");
        sync.processNext();
        verify(research, never()).persist(eq(65L), anyString(), any());
        assertThat(db.queryForObject("SELECT status FROM market_data_job WHERE product_id=65", String.class)).isEqualTo("RUNNING");
    }

    @Test
    void disabledServiceDoesNoWork() {
        MarketDataSyncService disabled =
                new MarketDataSyncService(db, products, analysis, historyPreparation, false, 10);
        disabled.refreshCatalogs();
        disabled.processNext();
        verify(analysis, never()).marketCatalog(anyString());
        verify(historyPreparation, never()).prepareProduct(any(), anyString(), anyBoolean());
    }

    private static Map<String, Object> catalogEntry(String type, String market, String code,
                                                    String name, String currency) {
        return Map.of("productType", type, "market", market, "code", code,
                "name", name, "currency", currency, "exchange", market, "source", "AKSHARE_A_LIST");
    }
}
