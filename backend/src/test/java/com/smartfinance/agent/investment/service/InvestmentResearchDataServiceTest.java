package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InvestmentResearchDataServiceTest {
    private Path file;
    private JdbcTemplate db;
    private InvestmentResearchDataService service;
    private TransactionTemplate transaction;

    @BeforeEach void setup() throws Exception {
        file = Files.createTempFile("research-data-", ".db");
        var source = new DriverManagerDataSource("jdbc:sqlite:" + file, "", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/sqlite").load().migrate();
        db = new JdbcTemplate(source);
        db.update("INSERT INTO investment_product(id,product_type,market,code,name,currency,status) "
                + "VALUES(1,'STOCK','SSE','600000','测试','CNY','ACTIVE')");
        service = new InvestmentResearchDataService(db, mock(AnalysisServiceClient.class), new ObjectMapper());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() throws Exception { Files.deleteIfExists(file); }

    @Test void repeatsAreIdempotentAndReversionKeepsEveryObservation() {
        save(row("a", "2026-10-01T00:00:00Z"));
        save(row("a", "2026-10-02T00:00:00Z"));
        assertThat(count()).isEqualTo(1);
        save(row("b", "2026-10-03T00:00:00Z"));
        save(row("a", "2026-10-04T00:00:00Z"));
        assertThat(count()).isEqualTo(3);
        assertThat(items("2026-09-30T23:59:59Z")).isEmpty();
        assertThat(items("2026-10-02T12:00:00Z").get(0)).containsEntry("content_hash", "a".repeat(64));
        assertThat(items("2026-10-03T12:00:00Z").get(0)).containsEntry("content_hash", "b".repeat(64));
        assertThat(items("2026-10-04T12:00:00Z").get(0)).containsEntry("content_hash", "a".repeat(64));
    }

    @Test void dateOnlyFilingsUseNextDayInPublicationTimezone() {
        Map<String,Object> record = row("a", "2026-10-04T15:00:00Z");
        record.put("publishedDate", "2026-10-04");
        record.put("availabilityBasis", "FILED_DATE");
        record.put("publicationTimezone", "America/New_York");
        save(record);
        assertThat(items("2026-10-05T03:59:59Z")).isEmpty();
        assertThat(items("2026-10-05T04:00:00Z")).hasSize(1);
        record = row("b", "2026-10-06T00:00:00Z");
        record.put("publishedDate", "2026-10-04");
        record.put("availabilityBasis", "FILED_DATE");
        record.put("publicationTimezone", "America/New_York");
        save(record);
        assertThat(items("2026-10-05T12:00:00Z").get(0)).containsEntry("content_hash", "a".repeat(64));
        assertThat(items("2026-10-06T00:00:00Z").get(0)).containsEntry("availability_basis", "FIRST_OBSERVED");
    }

    @Test void invalidBatchRollsBackValidRows() {
        Map<String,Object> invalid = row("b", "2026-10-04T00:00:00Z");
        invalid.put("recordKey", "invalid");
        assertThatThrownBy(() -> transaction.execute(tx -> service.persist(1, "FINANCIALS",
                response(List.of(row("a", "2026-10-04T00:00:00Z"), invalid))))).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    private void save(Map<String,Object> row) { transaction.execute(tx -> service.persist(1, "FINANCIALS", response(List.of(row)))); }
    private int count() { return db.queryForObject("SELECT COUNT(*) FROM investment_research_record", Integer.class); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(String cutoff) {
        return (List<Map<String,Object>>) service.read(1,"FINANCIALS",OffsetDateTime.parse(cutoff),1,100).get("items");
    }
    private static Map<String,Object> response(List<Map<String,Object>> rows) {
        return Map.of("dataset","FINANCIALS","adapterVersion","test-v1","records",rows);
    }
    private static Map<String,Object> row(String version, String observed) {
        Map<String,Object> row = new HashMap<>();
        row.put("recordKey","c".repeat(64)); row.put("contentHash",version.repeat(64));
        row.put("provider","TEST"); row.put("observedAt",observed);
        row.put("asOfDate","2025-12-31"); row.put("availabilityBasis","FIRST_OBSERVED");
        row.put("payload",Map.of("raw",Map.of("value",version)));
        return row;
    }
}
