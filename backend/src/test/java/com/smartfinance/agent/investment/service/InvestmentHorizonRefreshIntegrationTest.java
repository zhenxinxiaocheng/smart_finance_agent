package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.config.MyBatisPlusConfig;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonSettingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = InvestmentHorizonRefreshIntegrationTest.Configuration.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:horizon_refresh;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql"
})
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InvestmentHorizonRefreshIntegrationTest {
    @Autowired private InvestmentHorizonService horizons;
    @Autowired private InvestmentDataJobService jobs;
    @Autowired private MarketDataDemandService demand;
    @Autowired private MarketDataRequirementService requirements;
    @Autowired private JdbcTemplate db;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @MockBean private InvestmentDetailCacheService detailCache;
    @MockBean private InvestmentQuoteAvailabilityService availability;
    @MockBean private InvestmentDataQualityService qualityService;

    @BeforeEach
    void seedPreparedAssets() {
        org.mockito.Mockito.when(availability.target(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call->call.getArgument(1));
        db.update("INSERT INTO investment_product(id,product_type,market,code,name) VALUES(21,'STOCK','SSE','600000','test')");
        db.update("INSERT INTO investment_asset(id,user_id,account_id,product_id,deleted) VALUES(11,7,1,21,0),(12,8,1,21,0),(13,7,1,21,1)");
        for (long[] owner : List.of(new long[]{7, 11}, new long[]{8, 12})) {
            var job = jobs.ensureQueued(owner[0], owner[1], 21L, "STOCK", false);
            LocalDateTime now = requirements.now();
            assertThat(jobs.claim(job.getId(), now, now.plusMinutes(5), "seed")).isTrue();
            assertThat(jobs.markSucceeded(job.getId(), "seed", 500, now)).isTrue();
        }
        var row = receipt();
        requirements.recordPrepared(21L, "PRICE", MarketDataRequirementService.day(row.get("start_date")),
                MarketDataRequirementService.day(row.get("target_date")));
        assertThat(demand.preparedForAnalysis(7L, 11L, 21L)).isTrue();
    }

    @Test
    void changingTargetDaysQueuesAnalysisEvenWhenTheExistingReceiptStillCoversTheWindow() {
        Map<String, Object> before = receipt();

        horizons.saveGlobal(7L, targetPreference());

        assertThat(receipt()).containsEntry("start_date", before.get("start_date"))
                .containsEntry("prepared_start_date", before.get("prepared_start_date"))
                .containsEntry("prepared_end_date", before.get("prepared_end_date"));
        assertThat(demand.preparedForAnalysis(7L, 11L, 21L)).isTrue();
        assertThat(db.queryForMap("SELECT status,force_refresh FROM investment_data_job WHERE asset_id=11"))
                .containsEntry("status", "QUEUED");
        assertThat(db.queryForObject("SELECT force_refresh FROM investment_data_job WHERE asset_id=11", Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT status FROM investment_data_job WHERE asset_id=12", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_data_job WHERE asset_id=13", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM market_data_job", Integer.class)).isZero();
    }

    @Test
    void rollingBackPreferenceSaveAlsoRollsBackTheAnalysisQueue() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            horizons.saveGlobal(7L, targetPreference());
            assertThat(db.queryForObject("SELECT status FROM investment_data_job WHERE asset_id=11", String.class))
                    .isEqualTo("QUEUED");
            tx.setRollbackOnly();
        });

        assertThat(db.queryForObject("SELECT COUNT(*) FROM investment_horizon_profile", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT status FROM investment_data_job WHERE asset_id=11", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void savingPreferencesDoesNotResetAnActiveWorkerLease() {
        db.update("UPDATE investment_data_job SET status='RUNNING',lease_token='active-worker',lease_until=? WHERE asset_id=11",
                requirements.now().plusMinutes(5));

        horizons.saveGlobal(7L, targetPreference());

        assertThat(db.queryForMap("SELECT status,lease_token,force_refresh FROM investment_data_job WHERE asset_id=11"))
                .containsEntry("status", "RUNNING").containsEntry("lease_token", "active-worker");
        assertThat(db.queryForObject("SELECT force_refresh FROM investment_data_job WHERE asset_id=11", Boolean.class)).isFalse();
    }

    private Map<String, Object> receipt() {
        return db.queryForMap("SELECT * FROM market_data_requirement WHERE source_type='ASSET' AND source_id='11' AND user_id=7");
    }

    private static HorizonProfileRequest targetPreference() {
        return new HorizonProfileRequest(List.of(new HorizonSettingRequest("SHORT", "短期", 10, 5, 20, 15, true)));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.smartfinance.agent.investment.mapper")
    @Import({MyBatisPlusConfig.class, InvestmentHorizonProperties.class, InvestmentHorizonServiceImpl.class,
            InvestmentDataJobService.class, MarketDataDemandService.class, MarketDataRequirementService.class})
    static class Configuration { }
}
