package com.smartfinance.agent.investment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MarketDataSchemaEvolutionTest {
    @TempDir Path directory;

    @Test
    void upgradingV10PreservesProductsAndMarksLegacyDataWithoutClaimingItIsCurrent() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve(UUID.randomUUID() + ".db"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite")
                .target("10").load().migrate();
        var db = new JdbcTemplate(source);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency) VALUES(41,'STOCK','SSE','600000','test','CNY')");
        db.update("INSERT INTO product_daily_quote(product_id,trade_date,adjust_type,close_price,source,adapter_version) VALUES(41,'2026-01-05','NONE',10,'TEST','old')");

        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load();
        migration.migrate();

        assertThat(db.queryForList("PRAGMA table_info(product_daily_quote)").stream()
                .map(row -> row.get("name"))).contains("data_contract_version");
        assertThat(db.queryForMap("SELECT product_id,close_price,data_contract_version FROM product_daily_quote"))
                .containsEntry("product_id", 41).containsEntry("close_price", 10)
                .containsEntry("data_contract_version", null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_product WHERE id=41", Integer.class)).isEqualTo(1);
        assertThat(db.queryForList("PRAGMA table_info(market_data_collection_batch)")).isNotEmpty();
        assertThat(db.queryForList("PRAGMA table_info(investment_product)").stream().map(row->row.get("name")))
                .doesNotContain("history_coverage_complete");
        assertThat(db.queryForList("PRAGMA table_info(investment_research_record)").stream()
                .map(row -> row.get("name"))).contains("data_contract_version", "source_content_hash");
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
