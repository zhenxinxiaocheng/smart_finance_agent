package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.quant.PaperTradingService;
import com.smartfinance.agent.investment.quant.QuantPaperProperties;
import com.smartfinance.agent.investment.quant.QuantPrediction;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.QuantService;
import com.smartfinance.agent.investment.quant.QuantServiceImpl;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import com.smartfinance.agent.wealth.service.WealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:quant_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Import(QuantServiceImpl.class)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class QuantServiceImplIntegrationTest {
    private static final String JOB_ID = "a".repeat(32);
    private static final String DATASET_VERSION = "b".repeat(64);
    private static final String FEATURE_VERSION = "c".repeat(64);
    private static final String MODEL_VERSION = "d".repeat(64);
    private static final String STRATEGY_VERSION = "strategy-" + "e".repeat(32);

    @Autowired
    private QuantService quantService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private AnalysisServiceClient analysisServiceClient;
    @MockBean
    private InvestmentHorizonService horizonService;
    @MockBean
    private WealthService wealthService;
    @MockBean
    private PaperTradingService paperTradingService;
    @MockBean
    private QuantPaperProperties paperProperties;
    @MockBean
    private QuantBenchmarkProfileService benchmarkProfileService;

    @BeforeEach
    void setUp() {
        reset(analysisServiceClient, paperTradingService);
        jdbc.update("""
                INSERT INTO investment_account
                    (id, user_id, account_name, account_type, base_currency, deleted)
                VALUES (10, 7, 'Primary', 'BROKER', 'CNY', 0)
                """);
        jdbc.update("""
                INSERT INTO investment_product
                    (id, product_type, market, code, name, currency, status)
                VALUES (11, 'STOCK', 'SSE', '600519', 'Kweichow Moutai', 'CNY', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO investment_asset
                    (id, user_id, account_id, product_id, sync_status, deleted)
                VALUES (12, 7, 10, 11, 'SUCCESS', 0)
                """);
        jdbc.update("""
                INSERT INTO quant_job
                    (user_id, asset_id, external_job_id, job_type, status, dataset_version,
                     horizon_profile_version, horizon_code, horizon_days, started_at)
                VALUES (7, 12, ?, 'TRAIN_PREDICT', 'RUNNING', ?, 'profile-v1', 'WAVE', 20, CURRENT_TIMESTAMP)
                """, JOB_ID, DATASET_VERSION);
        jdbc.update("""
                INSERT INTO quant_training_run
                    (external_job_id, dataset_version, quant_config_version, product_type,
                     horizon_days, status, metrics_json, started_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'RUNNING', '{}', CURRENT_TIMESTAMP)
                """, JOB_ID, DATASET_VERSION);
    }

    @Test
    void completedTrainingJobPersistsVersionedArtifactsAndQueuesPaperOrder() {
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(completedRemoteJob());

        Map<String, Object> view = quantService.job(7L, JOB_ID);

        assertThat(view)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("featureSetVersion", FEATURE_VERSION)
                .containsEntry("modelVersion", MODEL_VERSION)
                .containsEntry("strategyVersion", STRATEGY_VERSION);
        assertThat(count("quant_feature_set")).isEqualTo(1);
        assertThat(count("quant_prediction")).isEqualTo(1);
        assertThat(count("quant_model_version")).isEqualTo(1);
        assertThat(count("quant_validation_report")).isEqualTo(1);
        assertThat(count("quant_strategy_version")).isEqualTo(1);
        assertThat(count("quant_backtest_run")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_strategy_version WHERE strategy_version = ?",
                String.class,
                STRATEGY_VERSION
        )).isEqualTo("PAPER");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_training_run WHERE external_job_id = ?",
                String.class,
                JOB_ID
        )).isEqualTo("SUCCEEDED");
        verify(paperTradingService).queueValidatedPrediction(
                eq(7L),
                any(),
                any(QuantPrediction.class),
                eq("VALIDATED")
        );
    }

    @Test
    void draftModelNeverCreatesOrderEvenWhenProbabilityIsOneHundredPercent() {
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(draftRemoteJob());

        quantService.job(7L, JOB_ID);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_model_version WHERE model_version = ?",
                String.class,
                MODEL_VERSION
        )).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT target_weight FROM quant_prediction WHERE model_version = ?",
                java.math.BigDecimal.class,
                MODEL_VERSION
        )).isEqualByComparingTo("0");
        verify(paperTradingService, never()).queueValidatedPrediction(any(), any(), any(), any());
    }

    @Test
    void validatedResearchExperimentWaitsForExplicitPaperPromotion() {
        jdbc.update(
                "UPDATE quant_job SET experiment_fingerprint = ? WHERE external_job_id = ?",
                "f".repeat(64),
                JOB_ID
        );
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(completedRemoteJob());

        quantService.job(7L, JOB_ID);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_model_version WHERE model_version = ?",
                String.class,
                MODEL_VERSION
        )).isEqualTo("VALIDATED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_strategy_version WHERE strategy_version = ?",
                String.class,
                STRATEGY_VERSION
        )).isEqualTo("DRAFT");
        verify(paperTradingService, never()).queueValidatedPrediction(any(), any(), any(), any());
    }

    @Test
    void failedJobPersistsStructuredFailureInsteadOfOldResultMessage() {
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(Map.of(
                "jobId", JOB_ID,
                "status", "FAILED",
                "errorCode", "JOB_FAILED",
                "errorSummary", "训练进程退出",
                "userMessage", "训练执行失败，请查看错误摘要"
        ));

        Map<String, Object> view = quantService.job(7L, JOB_ID);

        assertThat(view)
                .containsEntry("status", "FAILED")
                .containsEntry("errorCode", "JOB_FAILED")
                .containsEntry("errorSummary", "训练进程退出")
                .containsEntry("userMessage", "训练执行失败，请查看错误摘要");
        assertThat(view.get("userMessage").toString()).doesNotContain("上一份有效结果");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Map<String, Object> completedRemoteJob() {
        Map<String, Object> metrics = Map.of(
                "sharpe", 0.82,
                "maximumDrawdown", -0.08,
                "policyMaximumBrierScore", 0.25,
                "policyMinimumRealizedExcessReturn", 0.0,
                "policyConsecutiveFailuresBeforeRetirement", 3,
                "policyMinimumPaperTradingDays", 60
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("featureSetVersion", FEATURE_VERSION);
        result.put("featureArtifactUri", "quant-features/test.parquet");
        result.put("featureArtifactHash", "f".repeat(64));
        result.put("featureSchema", List.of("momentum_primary", "realized_volatility"));
        result.put("quantConfigVersion", "quant-research-v2");
        result.put("modelVersion", MODEL_VERSION);
        result.put("modelFileHash", "1".repeat(64));
        result.put("modelStatus", "VALIDATED");
        result.put("validationReport", Map.of(
                "passed", true,
                "lifecycle", "VALIDATED",
                "failureCodes", List.of(),
                "checks", List.of()
        ));
        result.put("strategyVersion", STRATEGY_VERSION);
        result.put("asOfDate", "2026-07-24");
        result.put("probabilityPositiveExcess", 0.68);
        result.put("expectedExcessReturn", 0.025);
        result.put("predictionInterval", List.of(0.005, 0.045));
        result.put("confidence", "HIGH");
        result.put("action", "ADD");
        result.put("targetWeight", 0.10);
        result.put("marketRegime", "UPTREND");
        result.put("benchmarkCode", "CSI300");
        result.put("roundTripCostBps", 18.0);
        result.put("topFactors", List.of(Map.of("name", "momentum_primary", "value", 0.12)));
        result.put("riskFlags", List.of());
        result.put("backtestSummary", metrics);
        return Map.of(
                "jobId", JOB_ID,
                "type", "TRAIN_PREDICT",
                "status", "SUCCEEDED",
                "result", result
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> draftRemoteJob() {
        Map<String, Object> remote = completedRemoteJob();
        Map<String, Object> result = (Map<String, Object>) remote.get("result");
        result.put("probabilityPositiveExcess", 1.0);
        result.put("targetWeight", 0.75);
        result.put("modelStatus", "DRAFT");
        result.put("validationReport", Map.of(
                "passed", false,
                "lifecycle", "DRAFT",
                "failureCodes", List.of("MODEL_REJECTED"),
                "checks", List.of()
        ));
        return remote;
    }
}
