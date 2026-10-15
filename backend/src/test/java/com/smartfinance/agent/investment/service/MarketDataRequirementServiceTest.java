package com.smartfinance.agent.investment.service;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class MarketDataRequirementServiceTest {
    @Test void aNewUserCannotReuseCompletionDatesProducedByAnOldRule() {
        service.save(1,"DETAIL","1",1,"PRICE","REQUIRED",today.minusDays(10),today,false,today.plusDays(1).atStartOfDay());
        db.update("UPDATE market_data_requirement SET prepared_start_date=start_date,prepared_end_date=target_date,prepared_contract_version='old'");
        service.save(2,"DETAIL","1",1,"PRICE","REQUIRED",today.minusDays(10),today,false,today.plusDays(1).atStartOfDay());
        var demand=new MarketDataDemandService(db,service,org.mockito.Mockito.mock(InvestmentHorizonService.class),new com.smartfinance.agent.investment.config.InvestmentHorizonProperties());
        assertThat(demand.prepared(2,"DETAIL","1",1,today)).isFalse();
    }

    @Test void sharedCurrentSourceEvidenceSurvivesReleaseOfTheOriginalUsersDemand() {
        var collections=new com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository(db);
        for(String adjustment:List.of("NONE","QFQ"))collections.record(1,"PRICE",adjustment,today.minusDays(10),today,
                Map.of("dataContractVersion",collections.currentVersion(1,"PRICE"),"result","EMPTY","records",List.of()));
        service.save(1,"DETAIL","1",1,"PRICE","REQUIRED",today.minusDays(10),today,false,today.plusDays(1).atStartOfDay());
        service.release(1,"DETAIL","1");
        service.save(2,"DETAIL","1",1,"PRICE","REQUIRED",today.minusDays(10),today,false,today.plusDays(1).atStartOfDay());
        var demand=new MarketDataDemandService(db,service,org.mockito.Mockito.mock(InvestmentHorizonService.class),new com.smartfinance.agent.investment.config.InvestmentHorizonProperties());
        assertThat(demand.prepared(2,"DETAIL","1",1,today)).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_collection_batch",Integer.class)).isEqualTo(2);
    }
    Path file; JdbcTemplate db; MarketDataRequirementService service;
    final LocalDate today=LocalDate.of(2026,10,5);
    @BeforeEach void setup() throws Exception {
        file=Files.createTempFile("market-requirements-", ".db");
        var source=new DriverManagerDataSource("jdbc:sqlite:"+file,"","");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db=new JdbcTemplate(source);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) VALUES(1,'STOCK','SSE','600000','test','CNY','ACTIVE')");
        service=new MarketDataRequirementService(db,Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC));
    }
    @AfterEach void cleanup() throws Exception { Files.deleteIfExists(file); }
    void request(long user,String source,LocalDate from,LocalDate to,LocalDateTime expires) {
        service.save(user,"DETAIL",source,1L,"PRICE","REQUIRED",from,to,false,expires);
    }
    @Test void repeatedRequestsAreIdempotentAndDifferentUsersRemainIndependent() {
        request(1,"page",today.minusDays(20),today,today.plusDays(1).atStartOfDay());
        request(1,"page",today.minusDays(40),today,today.plusDays(1).atStartOfDay());
        request(2,"page",today.minusDays(30),today,today.plusDays(1).atStartOfDay());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_requirement",Integer.class)).isEqualTo(2);
        assertThat(service.windows(1L,"PRICE",today)).hasSize(1);
        assertThat(service.windows(1L,"PRICE",today).get(0).start()).isEqualTo(today.minusDays(40));
        service.release(1,"DETAIL","page");
        assertThat(service.windows(1L,"PRICE",today).get(0).start()).isEqualTo(today.minusDays(30));
    }
    @Test void disjointWindowsDoNotDownloadTheUnusedYearsBetweenThem() {
        request(1,"older",today.minusYears(10),today.minusYears(9),today.plusDays(1).atStartOfDay());
        request(1,"newer",today.minusDays(20),today,today.plusDays(1).atStartOfDay());
        assertThat(service.windows(1L,"PRICE",today)).hasSize(2);
    }
    @Test void expiredDemandStopsWorkWithoutDeletingData() {
        request(1,"expired",today.minusDays(20),today,today.minusDays(1).atStartOfDay());
        assertThat(service.windows(1L,"PRICE",today)).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_requirement",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product",Integer.class)).isEqualTo(1);
    }
    @Test void invalidWindowCannotCreateWork() {
        assertThatThrownBy(()->request(1,"invalid",today,today.minusDays(1),today.plusDays(1).atStartOfDay()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_requirement",Integer.class)).isZero();
    }
    @Test void detailDemandUsesConfiguredHorizonAndClipsKnownListingDate() {
        var properties=new com.smartfinance.agent.investment.config.InvestmentHorizonProperties();
        properties.setMaxHistoryTradingDays(2500);properties.setMinimumHistoryTradingDays(20);
        properties.setIndicatorWarmupTradingDays(250);properties.setHistoryMultiplier(3);
        properties.setCalendarDaysPerYear(365);properties.setTradingDaysPerYear(240);properties.setCalendarBufferDays(30);
        var horizons=org.mockito.Mockito.mock(InvestmentHorizonService.class);
        var profile=org.mockito.Mockito.mock(com.smartfinance.agent.investment.domain.ResolvedHorizonProfile.class);
        org.mockito.Mockito.when(profile.requiredHistoryDays()).thenReturn(60);
        org.mockito.Mockito.when(horizons.resolve(1L,null)).thenReturn(profile);
        db.update("UPDATE investment_product SET listing_date=? WHERE id=1",today.minusDays(10));
        var demand=new MarketDataDemandService(db,service,horizons,properties);
        demand.prepareDetail(1L,1L);
        assertThat(db.queryForObject("SELECT start_date FROM market_data_requirement",String.class)).isEqualTo(today.minusDays(10).toString());
        demand.prepareDetail(1L,1L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_requirement",Integer.class)).isEqualTo(1);
    }
    @Test void publishedFundHistoryCanBeAnalyzedWithoutAFullCalendarReceipt() {
        db.update("UPDATE investment_product SET product_type='MUTUAL_FUND',market='FUND_CN',inception_date=? WHERE id=1",today.minusDays(30));
        db.update("INSERT INTO investment_asset(id,user_id,account_id,product_id,deleted) VALUES(13,1,1,1,0)");
        service.save(1,"ASSET","13",1,"PRICE","REQUIRED",today.minusDays(30),today,true,null);
        for(int i=0;i<25;i++)db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,total_return_index,source,data_contract_version) VALUES(1,?,'NONE',1,1,'TEST',?)",today.minusDays(30).plusDays(i),service.currentVersion(1,"PRICE"));
        var properties=new com.smartfinance.agent.investment.config.InvestmentHorizonProperties();
        properties.setMinimumHistoryTradingDays(20);
        var demand=new MarketDataDemandService(db,service,org.mockito.Mockito.mock(InvestmentHorizonService.class),properties);
        var quality=org.mockito.Mockito.mock(InvestmentDataQualityService.class);
        org.mockito.Mockito.when(quality.adjustType("MUTUAL_FUND","FUND_CN")).thenReturn("NONE");
        demand.setDataQuality(quality);
        assertThat(demand.preparedForAnalysis(1,13,1)).isTrue();
        assertThat(demand.preparedForAnalysis(2,13,1)).isFalse();
        assertThat(demand.prepared(1,"ASSET","13",1,today)).isFalse();
        db.update("UPDATE product_daily_quote SET total_return_index=NULL");
        assertThat(demand.preparedForAnalysis(1,13,1)).isFalse();
    }

    @Test void indexWatchMatchesCanonicalCatalogCodeAndStopsOnRemoval() {
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) VALUES(2,'INDEX','CN_INDEX','CN_INDEX:000300','test-index','CNY','ACTIVE')");
        db.update("INSERT INTO investment_index_watchlist(id,user_id,index_code,display_name,market,deleted) VALUES(42,1,'CN_INDEX:000300','test-index','CN',0)");
        var properties=new com.smartfinance.agent.investment.config.InvestmentHorizonProperties();
        properties.setMaxHistoryTradingDays(2500);properties.setMinimumHistoryTradingDays(20);
        properties.setIndicatorWarmupTradingDays(250);properties.setHistoryMultiplier(3);
        properties.setCalendarDaysPerYear(365);properties.setTradingDaysPerYear(240);properties.setCalendarBufferDays(30);
        var horizons=org.mockito.Mockito.mock(InvestmentHorizonService.class);
        var profile=org.mockito.Mockito.mock(com.smartfinance.agent.investment.domain.ResolvedHorizonProfile.class);
        org.mockito.Mockito.when(profile.requiredHistoryDays()).thenReturn(60);
        org.mockito.Mockito.when(horizons.resolve(1L,null)).thenReturn(profile);
        new MarketDataDemandService(db,service,horizons,properties).reconcileOwners();
        assertThat(service.active()).hasSize(1);
        assertThat(service.active().get(0)).containsEntry("source_type","INDEX_WATCH").containsEntry("source_id","42");
        db.update("UPDATE investment_index_watchlist SET deleted=1 WHERE id=42");
        assertThat(service.active()).isEmpty();
    }
}
