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

    @Test void identicalSourceFactsAreRestoredUnderTheNewContractWithoutChangingAvailability() {
        var original=row("a","2026-10-01T00:00:00Z");
        original.put("sourceContentHash","c".repeat(64));
        transaction.executeWithoutResult(status->service.persist(1,"FINANCIALS",
                Map.of("dataset","FINANCIALS","adapterVersion","old","records",List.of(original))));
        var upgraded=row("a","2026-10-14T00:00:00Z");
        upgraded.put("sourceContentHash","c".repeat(64));
        transaction.executeWithoutResult(status->service.persist(1,"FINANCIALS",
                Map.of("dataset","FINANCIALS","adapterVersion","new","dataContractVersion",currentContract(),"records",List.of(upgraded))));
        assertThat(count()).isEqualTo(2);
        var current=db.queryForMap("SELECT data_contract_version,available_at,observed_at,first_observed_at FROM investment_research_record ORDER BY id DESC LIMIT 1");
        assertThat(current).containsEntry("data_contract_version",currentContract());
        assertThat(current.get("available_at").toString()).startsWith("2026-10-01");
        assertThat(current.get("first_observed_at").toString()).startsWith("2026-10-01");
        assertThat(current.get("observed_at").toString()).startsWith("2026-10-14");
    }

    @Test void legacyNumericSourceFactsKeepTheirOriginalAvailabilityWhenReacquired() throws Exception {
        var original=row("a","2026-10-01T00:00:00Z");
        original.put("payload",Map.of("section","income","raw",Map.of("revenue",10000000.0)));
        save(original);
        db.update("UPDATE investment_research_record SET data_contract_version=NULL,source_content_hash=NULL,first_observed_at=NULL");
        var upgraded=row("b","2026-10-14T00:00:00Z");
        upgraded.put("payload",original.get("payload"));
        String pythonJson="{\"raw\":{\"revenue\":10000000.0},\"section\":\"income\"}";
        upgraded.put("sourceContentHash",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(pythonJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        save(upgraded);
        var latest=db.queryForMap("SELECT available_at,first_observed_at FROM investment_research_record ORDER BY id DESC LIMIT 1");
        assertThat(latest.get("available_at").toString()).startsWith("2026-10-01");
        assertThat(latest.get("first_observed_at").toString()).startsWith("2026-10-01");
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
        return Map.of("dataset","FINANCIALS","adapterVersion","test-v1","dataContractVersion",currentContract(),"records",rows);
    }
    private static String currentContract() { return com.smartfinance.agent.investment.marketdata.contract.MarketDataContract.forProduct("FINANCIALS","STOCK").version(); }
    private static Map<String,Object> row(String version, String observed) {
        Map<String,Object> row = new HashMap<>();
        row.put("recordKey","c".repeat(64)); row.put("contentHash",version.repeat(64));
        row.put("provider","TEST"); row.put("observedAt",observed);
        row.put("asOfDate","2025-12-31"); row.put("availabilityBasis","FIRST_OBSERVED");
        row.put("payload",Map.of("raw",Map.of("value",version)));
        return row;
    }
}
