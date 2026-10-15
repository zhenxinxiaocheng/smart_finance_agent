package com.smartfinance.agent.investment.service;

import static org.assertj.core.api.Assertions.*;
import com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketDataCollectionRepositoryTest {
    @TempDir Path directory;
    JdbcTemplate db;
    MarketDataCollectionRepository repository;
    LocalDate first=LocalDate.of(2026,1,5);

    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("data.db"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db=new JdbcTemplate(source);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency) VALUES(1,'INDEX','CN_INDEX','000300','test','CNY')");
        repository=new MarketDataCollectionRepository(db,Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"),ZoneOffset.UTC));
    }

    Map<String,Object> empty(String version) {
        return Map.of("dataContractVersion",version,"result","EMPTY","records",List.of());
    }

    @Test void oldRuleAndUnacknowledgedHolesCannotSatisfyANewRequest() {
        assertThatThrownBy(()->repository.record(1,"PRICE","NONE",first,first,empty("old")))
                .hasMessageContaining("版本");
        repository.record(1,"PRICE","NONE",first,first,empty(repository.currentVersion(1,"PRICE")));
        repository.record(1,"PRICE","NONE",first.plusDays(2),first.plusDays(2),empty(repository.currentVersion(1,"PRICE")));
        assertThat(repository.covers(1,"PRICE","NONE",first,first.plusDays(2))).isFalse();
        repository.record(1,"PRICE","NONE",first.plusDays(1),first.plusDays(1),empty(repository.currentVersion(1,"PRICE")));
        assertThat(repository.covers(1,"PRICE","NONE",first,first.plusDays(2))).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_collection_batch",Integer.class)).isEqualTo(3);
    }

    @Test void partialResponsesAndMissingRequiredFieldsNeverBecomeACompletedBatch() {
        assertThatThrownBy(()->repository.record(1,"PRICE","NONE",first,first,
                Map.of("dataContractVersion",repository.currentVersion(1,"PRICE"),"complete",false,"records",List.of())))
                .hasMessageContaining("未完成");
        assertThatThrownBy(()->repository.record(1,"PRICE","NONE",first,first,
                Map.of("dataContractVersion",repository.currentVersion(1,"PRICE"),"records",List.of(Map.of("data_date",first.toString())))))
                .hasMessageContaining("close");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_collection_batch",Integer.class)).isZero();
    }

    @Test void aBatchDoesNotMakeAnUnversionedStoredQuoteCurrent() {
        var row=Map.<String,Object>of("data_date",first.toString(),"close","10");
        repository.record(1,"PRICE","NONE",first,first,Map.of("dataContractVersion",repository.currentVersion(1,"PRICE"),"records",List.of(row)));
        assertThat(repository.covers(1,"PRICE","NONE",first,first)).isFalse();
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source) VALUES(1,?,'NONE',10,'TEST')",first);
        assertThat(repository.covers(1,"PRICE","NONE",first,first)).isFalse();
        db.update("UPDATE product_daily_quote SET data_contract_version=?",repository.currentVersion(1,"PRICE"));
        assertThat(repository.covers(1,"PRICE","NONE",first,first)).isTrue();
    }

    @Test void missingRequiredStoredFieldsInvalidateAnOtherwiseCurrentBatch() {
        db.update("UPDATE investment_product SET product_type='STOCK',market='NASDAQ' WHERE id=1");
        String contract=repository.currentVersion(1,"PRICE");
        var row=Map.<String,Object>of("data_date",first.toString(),"open","10","high","10","low","10","close","10","volume","100");
        repository.record(1,"PRICE","NONE",first,first,Map.of("dataContractVersion",contract,"records",List.of(row)));
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source,data_contract_version) VALUES(1,?,'NONE',10,'TEST',?)",first,contract);
        assertThat(repository.covers(1,"PRICE","NONE",first,first)).isFalse();
    }
}
