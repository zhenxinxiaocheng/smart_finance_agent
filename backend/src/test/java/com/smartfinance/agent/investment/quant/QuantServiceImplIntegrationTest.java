package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.quant.PaperTradingService;
import com.smartfinance.agent.investment.quant.QuantPaperProperties;
import com.smartfinance.agent.investment.quant.QuantPrediction;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.QuantActionPlanService;
import com.smartfinance.agent.investment.quant.QuantModelManagementService;
import com.smartfinance.agent.investment.quant.QuantInferenceOrchestrator;
import com.smartfinance.agent.investment.quant.QuantModelRegistryService;
import com.smartfinance.agent.investment.quant.QuantPredictionQueryService;
import com.smartfinance.agent.investment.quant.QuantService;
import com.smartfinance.agent.investment.quant.QuantServiceImpl;
import com.smartfinance.agent.investment.quant.QuantTrainingOrchestrator;
import com.smartfinance.agent.investment.quant.QuantTradingDecisionService;
import com.smartfinance.agent.investment.quant.QuantResearchUniversePreparationService;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentDataJobService;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ServiceIntegrationTestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:quant_service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,TRANSACTION",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Import({
        QuantServiceImpl.class,
        QuantPredictionQueryService.class,
        QuantActionPlanService.class,
        QuantModelManagementService.class,
        QuantInferenceOrchestrator.class,
        QuantModelRegistryService.class,
        QuantTrainingOrchestrator.class,
        QuantTradingDecisionService.class
})
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
    private QuantPredictionQueryService predictionQueryService;
    @Autowired
    private QuantActionPlanService actionPlanService;
    @Autowired
    private QuantModelManagementService modelManagementService;
    @Autowired
    private QuantInferenceOrchestrator inferenceOrchestrator;
    @Autowired
    private QuantModelRegistryService modelRegistryService;
    @Autowired
    private QuantTradingDecisionService tradingDecisionService;
    @Autowired
    private QuantTrainingOrchestrator trainingOrchestrator;
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
    @MockBean
    private InvestmentDataJobService investmentDataJobService;
    @MockBean
    private QuantResearchUniversePreparationService researchUniversePreparationService;

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
                     quant_config_version, product_type, metrics_json,
                     horizon_profile_version, horizon_code, horizon_days, started_at)
                VALUES (7, 12, ?, 'TRAIN_PREDICT', 'RUNNING', ?,
                        'quant-research-v2', 'STOCK', '{}',
                        'profile-v1', 'WAVE', 20, CURRENT_TIMESTAMP)
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
                "SELECT metrics_json FROM quant_job WHERE external_job_id = ?",
                String.class,
                JOB_ID
        )).contains("\"sharpe\":0.82");
        assertThat(jdbc.queryForObject(
                "SELECT profit_probability FROM quant_prediction WHERE model_version = ?",
                java.math.BigDecimal.class,
                MODEL_VERSION
        )).isEqualByComparingTo("0.68");
        assertThat(jdbc.queryForObject(
                "SELECT expected_net_return FROM quant_prediction WHERE model_version = ?",
                java.math.BigDecimal.class,
                MODEL_VERSION
        )).isEqualByComparingTo("0.025");
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

        Map<String, Object> view = quantService.job(7L, JOB_ID);

        assertThat(view)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("executionStatus", "COMPLETED")
                .containsEntry("trainingOutcome", "VALIDATION_FAILED")
                .containsEntry("deploymentStatus", "RESEARCH")
                .containsEntry("economicRole", "RISK_REFERENCE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_model_version WHERE model_version = ?",
                String.class,
                MODEL_VERSION
        )).isEqualTo("DRAFT");
        assertThat(count("quant_prediction")).isZero();
        verify(paperTradingService, never()).queueValidatedPrediction(any(), any(), any(), any());
    }

    @Test
    void incompleteTerminalResultStillPersistsCompletedValidationFailure() {
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(Map.of(
                "jobId", JOB_ID,
                "status", "SUCCEEDED",
                "executionStatus", "COMPLETED",
                "trainingOutcome", "VALIDATION_FAILED",
                "deploymentStatus", "RESEARCH",
                "result", Map.of("unexpected", true)
        ));

        Map<String, Object> view = quantService.job(7L, JOB_ID);

        assertThat(view)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("executionStatus", "COMPLETED")
                .containsEntry("trainingOutcome", "VALIDATION_FAILED")
                .containsEntry("deploymentStatus", "RESEARCH");
        assertThat(jdbc.queryForMap(
                """
                SELECT status, execution_status, training_outcome
                  FROM quant_job
                 WHERE external_job_id = ?
                """,
                JOB_ID
        )).containsEntry("STATUS", "SUCCEEDED")
                .containsEntry("EXECUTION_STATUS", "COMPLETED")
                .containsEntry("TRAINING_OUTCOME", "VALIDATION_FAILED");
        assertThat(count("quant_feature_set")).isZero();
        assertThat(count("quant_model_version")).isZero();
        assertThat(count("quant_prediction")).isZero();
        verify(paperTradingService, never()).queueValidatedPrediction(any(), any(), any(), any());
    }

    @Test
    void draftPredictionIsNeverReturnedToTheAssetDetailPage() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        jdbc.update("""
                INSERT INTO investment_data_quality_snapshot
                    (dataset_version, product_type, code, market, frequency, adjust_type,
                     provider, adapter_version, quality_config_version, quality_rule_set_version,
                     quality_status, decision, enforcement_mode, requested_start_date,
                     requested_end_date, sample_start_date, sample_end_date, fetched_at,
                     evaluated_at, manifest_json, report_json)
                VALUES (?, 'STOCK', '600519', 'SSE', 'DAILY', 'QFQ',
                        'TEST', 'test-v1', 'quality-v1', 'rules-v1',
                        'PASS', 'ALLOW', 'STRICT', DATE '2024-01-01',
                        DATE '2026-07-24', DATE '2024-01-01', DATE '2026-07-24',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}', '{}')
                """, DATASET_VERSION);
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'DRAFT', ?, '{}', CURRENT_TIMESTAMP)
                """, MODEL_VERSION, FEATURE_VERSION, "1".repeat(64));
        jdbc.update("""
                INSERT INTO quant_prediction
                    (user_id, asset_id, dataset_version, feature_set_version, model_version,
                     horizon_profile_version, horizon_code, horizon_days, as_of_date,
                     probability_positive_excess, expected_excess_return, interval_lower,
                     interval_upper, confidence, action, target_weight, top_factors_json,
                     risk_flags_json, backtest_summary_json)
                VALUES (7, 12, ?, ?, ?, 'profile-v1', 'WAVE', 20, DATE '2026-07-24',
                        1.0, 0.20, -0.05, 0.35, 'HIGH', 'BUY', 0.75, '[]', '[]', '{}')
                """, DATASET_VERSION, FEATURE_VERSION, MODEL_VERSION);

        Map<String, Object> result = predictionQueryService.latestAnalysis(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "UNAVAILABLE")
                .containsEntry("errorCode", "MODEL_UNAVAILABLE")
                .containsEntry("userMessage", "暂无有效量化模型");
        assertThat(result).doesNotContainKeys(
                "probabilityPositiveExcess",
                "expectedExcessReturn",
                "predictionInterval",
                "topFactors",
                "modelVersion"
        );
    }

    @Test
    void latestDraftPredictionDoesNotHidePreviouslyDeployedValidatedPrediction() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(100_000));
        when(wealthService.overview(7L)).thenReturn(overview);
        jdbc.update("""
                INSERT INTO investment_data_quality_snapshot
                    (dataset_version, product_type, code, market, frequency, adjust_type,
                     provider, adapter_version, quality_config_version, quality_rule_set_version,
                     quality_status, decision, enforcement_mode, requested_start_date,
                     requested_end_date, sample_start_date, sample_end_date, fetched_at,
                     evaluated_at, manifest_json, report_json)
                VALUES (?, 'STOCK', '600519', 'SSE', 'DAILY', 'QFQ',
                        'TEST', 'test-v1', 'quality-v1', 'rules-v1',
                        'PASS', 'ALLOW', 'STRICT', DATE '2024-01-01',
                        DATE '2026-07-24', DATE '2024-01-01', DATE '2026-07-24',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}', '{}')
                """, DATASET_VERSION);
        String validatedModel = "1".repeat(64);
        String draftModel = "2".repeat(64);
        String deployedStrategy = "strategy-" + "3".repeat(32);
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'VALIDATED', ?, '{}',
                        DATEADD('MINUTE', -2, CURRENT_TIMESTAMP))
                """, validatedModel, FEATURE_VERSION, "4".repeat(64));
        jdbc.update("""
                INSERT INTO quant_strategy_version
                    (strategy_version, model_version, product_type, status, validation_metrics_json)
                VALUES (?, ?, 'STOCK', 'PAPER', '{}')
                """, deployedStrategy, validatedModel);
        jdbc.update("""
                INSERT INTO quant_prediction
                    (user_id, asset_id, dataset_version, feature_set_version, model_version,
                     strategy_version, horizon_profile_version, horizon_code, horizon_days,
                     as_of_date, probability_positive_excess, expected_excess_return,
                     interval_lower, interval_upper, confidence, action, target_weight,
                     top_factors_json, risk_flags_json, backtest_summary_json, created_at)
                VALUES (7, 12, ?, ?, ?, ?, 'profile-v1', 'WAVE', 20, DATE '2026-07-24',
                        0.68, 0.025, -0.01, 0.06, 'MEDIUM', 'HOLD', 0.10,
                        '[]', '[]', '{}', DATEADD('MINUTE', -1, CURRENT_TIMESTAMP))
                """, DATASET_VERSION, FEATURE_VERSION, validatedModel, deployedStrategy);
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'DRAFT', ?, '{}',
                        CURRENT_TIMESTAMP)
                """, draftModel, FEATURE_VERSION, "5".repeat(64));
        jdbc.update("""
                INSERT INTO quant_prediction
                    (user_id, asset_id, dataset_version, feature_set_version, model_version,
                     horizon_profile_version, horizon_code, horizon_days, as_of_date,
                     probability_positive_excess, expected_excess_return, interval_lower,
                     interval_upper, confidence, action, target_weight, top_factors_json,
                     risk_flags_json, backtest_summary_json, created_at)
                VALUES (7, 12, ?, ?, ?, 'profile-v1', 'WAVE', 20, DATE '2026-07-24',
                        1.0, 0.20, -0.05, 0.35, 'HIGH', 'BUY', 0.75,
                        '[]', '[]', '{}', CURRENT_TIMESTAMP)
                """, DATASET_VERSION, FEATURE_VERSION, draftModel);

        Map<String, Object> result = quantService.latestAnalysis(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "READY")
                .containsEntry("modelLifecycle", "VALIDATED")
                .containsEntry("modelVersion", validatedModel)
                .containsEntry("deploymentStatus", "PAPER");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Replaced by absolute-return training behavior")
    void missingOfficialFundBenchmarkBlocksTrainingRequest() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        jdbc.update("""
                UPDATE investment_product
                SET product_type = 'MUTUAL_FUND', market = 'FUND_CN', code = '270042',
                    name = '广发纳斯达克100ETF联接人民币(QDII)A'
                WHERE id = 11
                """);
        jdbc.update("""
                INSERT INTO investment_data_quality_snapshot
                    (dataset_version, product_type, code, market, frequency, adjust_type,
                     provider, adapter_version, quality_config_version, quality_rule_set_version,
                     quality_status, decision, enforcement_mode, requested_start_date,
                     requested_end_date, sample_start_date, sample_end_date, fetched_at,
                     evaluated_at, manifest_json, report_json)
                VALUES (?, 'MUTUAL_FUND', '270042', 'FUND_CN', 'DAILY', 'NONE',
                        'TEST', 'test-v1', 'quality-v1', 'rules-v1',
                        'PASS', 'ALLOW', 'STRICT', DATE '2024-01-01',
                        DATE '2026-07-24', DATE '2024-01-01', DATE '2026-07-24',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}', '{}')
                """, DATASET_VERSION);
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES (11, DATE '2026-07-24', 1.25, 'NONE', 'TEST')
                """);
        when(benchmarkProfileService.resolveCached(
                eq("MUTUAL_FUND"),
                eq("270042"),
                any(),
                any(),
                any()
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                false,
                null,
                null,
                null,
                List.of(),
                "BENCHMARK_UNAVAILABLE",
                "官方基准数据尚未准备完成"
        ));

        Map<String, Object> result = trainingOrchestrator.refresh(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("errorCode", "BENCHMARK_UNAVAILABLE")
                .containsEntry("userMessage", "官方基准数据尚未准备完成，当前暂停模型训练");
        verify(analysisServiceClient, never()).createQuantJob(any());
    }

    @Test
    void missingOfficialFundBenchmarkStillStartsAbsoluteReturnTraining() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "Wave", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        jdbc.update("""
                UPDATE investment_product
                SET product_type = 'MUTUAL_FUND', market = 'FUND_CN', code = '270042'
                WHERE id = 11
                """);
        jdbc.update("""
                INSERT INTO investment_data_quality_snapshot
                    (dataset_version, product_type, code, market, frequency, adjust_type,
                     provider, adapter_version, quality_config_version, quality_rule_set_version,
                     quality_status, decision, enforcement_mode, requested_start_date,
                     requested_end_date, sample_start_date, sample_end_date, fetched_at,
                     evaluated_at, manifest_json, report_json)
                VALUES (?, 'MUTUAL_FUND', '270042', 'FUND_CN', 'DAILY', 'NONE',
                        'TEST', 'test-v1', 'quality-v1', 'rules-v1',
                        'PASS', 'ALLOW', 'STRICT', DATE '2024-01-01',
                        DATE '2026-07-24', DATE '2024-01-01', DATE '2026-07-24',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}', '{}')
                """, DATASET_VERSION);
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES (11, DATE '2026-07-24', 1.25, 'NONE', 'TEST')
                """);
        when(benchmarkProfileService.resolveCached(
                eq("MUTUAL_FUND"),
                eq("270042"),
                any(),
                any(),
                any()
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                false,
                "NASDAQ100_TR_CNY",
                "QDII_INDEX_FUND",
                null,
                List.of(),
                "BENCHMARK_UNAVAILABLE",
                "Official benchmark data is temporarily unavailable"
        ));
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(100_000));
        when(wealthService.overview(7L)).thenReturn(overview);
        when(analysisServiceClient.quantRuntimeManifest()).thenReturn(
                Map.of("runtimeVersion", "1".repeat(64))
        );
        when(analysisServiceClient.createQuantJob(any())).thenReturn(Map.of(
                "jobId", "8".repeat(32),
                "status", "QUEUED",
                "configVersion", "quant-research-v2"
        ));

        Map<String, Object> result = trainingOrchestrator.refresh(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "QUEUED")
                .containsEntry("jobId", "8".repeat(32));
        ArgumentCaptor<Map> request = ArgumentCaptor.forClass(Map.class);
        verify(analysisServiceClient).createQuantJob(request.capture());
        assertThat(castView(request.getValue()))
                .containsEntry("type", "AUTO_SEARCH")
                .containsEntry("modelFamily", "QDII_INDEX_FUND")
                .doesNotContainKeys("benchmarkRecords", "benchmarkProfileVersion");
        verify(investmentDataJobService).ensureBenchmarkQueued(7L, 12L, 11L);
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
    void validatedResearchModelCanBePromotedThroughModelRegistry() {
        jdbc.update(
                "UPDATE quant_job SET experiment_fingerprint = ? WHERE external_job_id = ?",
                "f".repeat(64),
                JOB_ID
        );
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(completedRemoteJob());
        quantService.job(7L, JOB_ID);

        modelRegistryService.activatePaperModel(7L, MODEL_VERSION);

        assertThat(jdbc.queryForMap(
                """
                SELECT status, deployment_role, user_id, asset_id, horizon_code
                FROM quant_strategy_version
                WHERE strategy_version = ?
                """,
                STRATEGY_VERSION
        )).containsEntry("STATUS", "PAPER")
                .containsEntry("DEPLOYMENT_ROLE", "CHALLENGER")
                .containsEntry("USER_ID", 7L)
                .containsEntry("ASSET_ID", 12L)
                .containsEntry("HORIZON_CODE", "WAVE");
        verify(paperTradingService).queueValidatedPrediction(
                eq(7L),
                any(),
                any(QuantPrediction.class),
                eq("VALIDATED")
        );
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

    @Test
    void paperAccountQueryIsOwnedByTradingDecisionService() {
        Map<String, Object> result = tradingDecisionService.paperAccount(7L);

        assertThat(result)
                .containsEntry("status", "NOT_STARTED")
                .containsEntry("cash", List.of())
                .containsEntry("positions", List.of());
    }

    @Test
    void actionPlanPausesWithoutADeployedModelAndDoesNotInventAnOrderAmount() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        insertAllowedQuality();

        Map<String, Object> result = actionPlanService.actionPlan(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "PAUSED")
                .containsEntry("action", "PAUSE")
                .containsEntry("targetWeight", null)
                .containsEntry("orderAmountCny", null)
                .containsEntry("estimatedQuantity", null)
                .containsEntry("userMessage", "暂无有效量化模型");
    }

    @Test
    void actionPlanReducesWhenCurrentWeightExceedsPositiveSignalTarget() {
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        insertAllowedQuality();
        jdbc.update("UPDATE investment_asset SET quantity = 300 WHERE id = 12");
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES (11, DATE '2026-07-24', 1510, 'QFQ', 'TEST')
                """);
        insertModel(MODEL_VERSION, "VALIDATED");
        jdbc.update("""
                INSERT INTO quant_strategy_version
                    (strategy_version, model_version, user_id, asset_id, product_type,
                     model_family, horizon_code, deployment_role, status,
                     validation_metrics_json, activated_at)
                VALUES (?, ?, 7, 12, 'STOCK', 'A_SHARE_STOCK', 'WAVE',
                        'CHALLENGER', 'PAPER', '{}', CURRENT_TIMESTAMP)
                """, STRATEGY_VERSION, MODEL_VERSION);
        jdbc.update("""
                INSERT INTO quant_prediction
                    (user_id, asset_id, dataset_version, feature_set_version, model_version,
                     strategy_version, horizon_profile_version, horizon_code, horizon_days,
                     as_of_date, probability_positive_excess, expected_excess_return,
                     interval_lower, interval_upper, confidence, action, target_weight,
                     top_factors_json, risk_flags_json, backtest_summary_json)
                VALUES (7, 12, ?, ?, ?, ?, 'profile-v1', 'WAVE', 20,
                        DATE '2026-07-24', 0.60, 0.01, -0.10, 0.15, 'MEDIUM',
                        'BUY_WATCH', 0.10, '[]', '[]', '{}')
                """, DATASET_VERSION, FEATURE_VERSION, MODEL_VERSION, STRATEGY_VERSION);
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(1_000_000));
        when(wealthService.overview(7L)).thenReturn(overview);

        Map<String, Object> result = actionPlanService.actionPlan(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("status", "READY")
                .containsEntry("action", "REDUCE")
                .containsEntry("currentWeight", new java.math.BigDecimal("0.45300000"))
                .containsEntry("targetWeight", new java.math.BigDecimal("0.1000000000"))
                .containsEntry("orderAmountCny", new java.math.BigDecimal("353000.00"))
                .containsEntry("estimatedQuantity", new java.math.BigDecimal("200"));
    }

    @Test
    void modelManagementSeparatesChampionAndChallengerForTheSelectedAsset() {
        String championModel = "1".repeat(64);
        String challengerModel = "2".repeat(64);
        insertModel(championModel, "PAPER_VERIFIED");
        insertModel(challengerModel, "VALIDATED");
        jdbc.update("""
                INSERT INTO quant_strategy_version
                    (strategy_version, model_version, user_id, asset_id, product_type,
                     model_family, horizon_code, deployment_role, status,
                     validation_metrics_json, activated_at)
                VALUES
                    (?, ?, 7, 12, 'STOCK', 'A_SHARE_STOCK', 'WAVE',
                     'CHAMPION', 'CHAMPION', '{}', CURRENT_TIMESTAMP),
                    (?, ?, 7, 12, 'STOCK', 'A_SHARE_STOCK', 'WAVE',
                     'CHALLENGER', 'PAPER', '{}', CURRENT_TIMESTAMP)
                """,
                "strategy-champion", championModel,
                "strategy-challenger", challengerModel
        );

        Map<String, Object> result = modelManagementService.management(7L, 12L, "WAVE");

        assertThat(result)
                .containsEntry("assetId", 12L)
                .containsEntry("automaticTraining", true);
        assertThat(castView(result.get("champion")))
                .containsEntry("modelVersion", championModel)
                .containsEntry("modelLifecycle", "PAPER_VERIFIED");
        assertThat(castView(result.get("challenger")))
                .containsEntry("modelVersion", challengerModel)
                .containsEntry("modelLifecycle", "VALIDATED");
    }

    @Test
    void rejectedDraftIsExposedOnlyAsNonTradableTechnicalSignal() {
        jdbc.update("""
                UPDATE quant_job
                   SET job_type = 'AUTO_SEARCH',
                       status = 'SUCCEEDED',
                       execution_status = 'COMPLETED',
                       training_outcome = 'VALIDATION_FAILED',
                       deployment_status = 'RESEARCH',
                       economic_role = 'RISK_REFERENCE',
                       error_code = 'MODEL_REJECTED',
                       user_message = '交易模型继续自动优化',
                       result_json = ?,
                       finished_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE external_job_id = ?
                """, """
                {
                  "modelStatus":"DRAFT",
                  "profitProbability":0.388,
                  "lossProbability":0.612,
                  "expectedNetReturn":-0.0025,
                  "predictionInterval":[-0.0365,0.0216],
                  "marketRegime":"HIGH_VOLATILITY",
                  "topFactors":[{"name":"trend_slope","contribution":-0.31}],
                  "riskReference":{
                    "marketRegime":"HIGH_VOLATILITY",
                    "annualizedVolatility":0.284,
                    "positionLimit":0.35,
                    "drawdownWarning":"当前波动与回撤风险偏高",
                    "tradable":false
                  },
                  "baselineComparison":{
                    "strongestBaseline":"BUY_AND_HOLD",
                    "netExcessVsStrongestBaseline":-0.021
                  },
                  "searchSummary":{
                    "technicalSignalAvailable":true,
                    "nextAction":"CONTINUE_ON_NEW_DATA_OR_VERSION",
                    "nextAdjustment":"延长信号窗口并使用波动率目标抑制换手"
                  }
                }
                """, JOB_ID);

        Map<String, Object> result = modelManagementService.management(7L, 12L, "WAVE");
        Map<String, Object> signal = castView(result.get("technicalSignal"));

        assertThat(signal)
                .containsEntry("status", "READY")
                .containsEntry("tradable", false)
                .containsEntry("economicRole", "RISK_REFERENCE")
                .containsEntry("profitProbability", 0.388)
                .containsEntry("positionLimit", 0.35)
                .containsEntry("marketRegime", "HIGH_VOLATILITY");
        assertThat(signal)
                .doesNotContainKeys("targetWeight", "orderAmountCny", "estimatedQuantity");
        assertThat(castView(result.get("training")))
                .containsEntry("executionStatus", "COMPLETED")
                .containsEntry("trainingOutcome", "VALIDATION_FAILED")
                .containsEntry("label", "执行完成、验证未通过；已保留风险参考")
                .containsEntry("continuation", "CONTINUE_ON_NEW_DATA_OR_VERSION");
    }

    @Test
    void automaticTrainingReusesOnlyTheSameRuntimeVersion() {
        String autoJobId = "6".repeat(32);
        String changedRuntimeJobId = "5".repeat(32);
        String runtimeVersion = "1".repeat(64);
        String changedRuntimeVersion = "2".repeat(64);
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        insertAllowedQuality();
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES
                    (11, DATE '2026-07-23', 1500, 'QFQ', 'TEST'),
                    (11, DATE '2026-07-24', 1510, 'QFQ', 'TEST')
                """);
        when(benchmarkProfileService.resolveCached(
                eq("STOCK"),
                eq("600519"),
                any(),
                any(),
                any()
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                false,
                null,
                null,
                null,
                List.of(),
                "BENCHMARK_UNAVAILABLE",
                "未配置股票辅助基准"
        ));
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(100_000));
        when(wealthService.overview(7L)).thenReturn(overview);
        when(analysisServiceClient.quantRuntimeManifest()).thenReturn(
                Map.of("runtimeVersion", runtimeVersion),
                Map.of("runtimeVersion", runtimeVersion),
                Map.of("runtimeVersion", changedRuntimeVersion)
        );
        when(analysisServiceClient.createQuantJob(any())).thenReturn(
                Map.of(
                        "jobId", autoJobId,
                        "status", "QUEUED",
                        "configVersion", "quant-research-v2"
                ),
                Map.of(
                        "jobId", changedRuntimeJobId,
                        "status", "QUEUED",
                        "configVersion", "quant-research-v2"
                )
        );

        Map<String, Object> first = trainingOrchestrator.refresh(7L, 12L, "WAVE");
        Map<String, Object> second = trainingOrchestrator.refresh(7L, 12L, "WAVE");
        Map<String, Object> afterRuntimeChange = trainingOrchestrator.refresh(7L, 12L, "WAVE");

        assertThat(first).containsEntry("jobId", autoJobId);
        assertThat(second)
                .containsEntry("jobId", autoJobId)
                .containsEntry("status", "QUEUED");
        assertThat(afterRuntimeChange).containsEntry("jobId", changedRuntimeJobId);
        verify(analysisServiceClient, times(2)).createQuantJob(any());
    }

    @Test
    void automaticTrainingDoesNotReuseIncompleteSucceededResult() {
        String incompleteJobId = "4".repeat(32);
        String replacementJobId = "3".repeat(32);
        String runtimeVersion = "1".repeat(64);
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        insertAllowedQuality();
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES
                    (11, DATE '2026-07-23', 1500, 'QFQ', 'TEST'),
                    (11, DATE '2026-07-24', 1510, 'QFQ', 'TEST')
                """);
        when(benchmarkProfileService.resolveCached(
                eq("STOCK"),
                eq("600519"),
                any(),
                any(),
                any()
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                false,
                null,
                null,
                null,
                List.of(),
                "BENCHMARK_UNAVAILABLE",
                "未配置股票辅助基准"
        ));
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(100_000));
        when(wealthService.overview(7L)).thenReturn(overview);
        when(analysisServiceClient.quantRuntimeManifest()).thenReturn(
                Map.of("runtimeVersion", runtimeVersion)
        );
        when(analysisServiceClient.createQuantJob(any())).thenReturn(
                Map.of(
                        "jobId", incompleteJobId,
                        "status", "QUEUED",
                        "configVersion", "quant-research-v2"
                ),
                Map.of(
                        "jobId", replacementJobId,
                        "status", "QUEUED",
                        "configVersion", "quant-research-v2"
                )
        );

        Map<String, Object> first = trainingOrchestrator.refresh(7L, 12L, "WAVE");
        jdbc.update("""
                UPDATE quant_job
                   SET status = 'SUCCEEDED',
                       execution_status = 'COMPLETED',
                       training_outcome = 'VALIDATION_FAILED',
                       result_json = '{"unexpected":true}'
                 WHERE external_job_id = ?
                """, incompleteJobId);
        Map<String, Object> replacement = trainingOrchestrator.refresh(7L, 12L, "WAVE");

        assertThat(first).containsEntry("jobId", incompleteJobId);
        assertThat(replacement).containsEntry("jobId", replacementJobId);
        assertThat(jdbc.queryForMap("""
                SELECT status, execution_status, training_outcome, error_code
                  FROM quant_job
                 WHERE external_job_id = ?
                """, incompleteJobId))
                .containsEntry("STATUS", "FAILED")
                .containsEntry("EXECUTION_STATUS", "FAILED")
                .containsEntry("TRAINING_OUTCOME", null)
                .containsEntry("ERROR_CODE", "INVALID_RESULT_CONTRACT");
        verify(analysisServiceClient, times(2)).createQuantJob(any());
    }

    @Test
    void deployedModelUsesSavedArtifactForPredictionInsteadOfRetraining() {
        String deployedModel = "7".repeat(64);
        String artifactHash = "8".repeat(64);
        String inferenceJobId = "9".repeat(32);
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(new HorizonSetting(
                        "WAVE", "波段", 10, 7, 45, 20, true, "ASSET")),
                List.of()
        ));
        insertAllowedQuality();
        jdbc.update("""
                INSERT INTO product_daily_quote
                    (product_id, trade_date, close_price, adjust_type, source)
                VALUES
                    (11, DATE '2026-07-23', 1500, 'QFQ', 'TEST'),
                    (11, DATE '2026-07-24', 1510, 'QFQ', 'TEST')
                """);
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'PAPER_VERIFIED',
                        ?, '{}', CURRENT_TIMESTAMP)
                """, deployedModel, FEATURE_VERSION, artifactHash);
        jdbc.update("""
                INSERT INTO quant_strategy_version
                    (strategy_version, model_version, user_id, asset_id, product_type,
                     model_family, horizon_code, deployment_role, status,
                     validation_metrics_json, activated_at)
                VALUES ('strategy-live', ?, 7, 12, 'STOCK', 'A_SHARE_STOCK', 'WAVE',
                        'CHAMPION', 'CHAMPION', '{}', CURRENT_TIMESTAMP)
                """, deployedModel);
        when(benchmarkProfileService.resolve(
                eq("STOCK"),
                eq("600519"),
                any(),
                any(),
                any()
        )).thenReturn(new QuantBenchmarkProfileService.ResolvedBenchmark(
                false,
                null,
                null,
                null,
                List.of(),
                "BENCHMARK_UNAVAILABLE",
                "未配置股票辅助基准"
        ));
        WealthOverviewResponse overview = new WealthOverviewResponse();
        overview.setTotalAssets(java.math.BigDecimal.valueOf(100_000));
        when(wealthService.overview(7L)).thenReturn(overview);
        when(analysisServiceClient.createQuantJob(any())).thenReturn(Map.of(
                "jobId", inferenceJobId,
                "status", "QUEUED",
                "configVersion", "quant-research-v2"
        ));

        Map<String, Object> result = inferenceOrchestrator.refresh(
                7L,
                12L,
                "WAVE"
        );

        assertThat(result)
                .containsEntry("jobId", inferenceJobId)
                .containsEntry("type", "PREDICT");
        ArgumentCaptor<Map> request = ArgumentCaptor.forClass(Map.class);
        verify(analysisServiceClient).createQuantJob(request.capture());
        assertThat(castView(request.getValue()))
                .containsEntry("type", "PREDICT")
                .containsEntry("modelVersion", deployedModel)
                .containsEntry("modelFileHash", artifactHash)
                .containsEntry("modelConfigVersion", "quant-research-v2")
                .containsEntry("modelStatus", "PAPER_VERIFIED")
                .containsEntry("strategyVersion", "strategy-live");
    }

    @Test
    void completedSavedModelInferencePersistsAFormalPrediction() {
        jdbc.update("""
                UPDATE quant_job
                SET job_type = 'PREDICT', model_version = ?, strategy_version = ?
                WHERE external_job_id = ?
                """, MODEL_VERSION, STRATEGY_VERSION, JOB_ID);
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, 'PAPER_VERIFIED',
                        ?, '{}', CURRENT_TIMESTAMP)
                """, MODEL_VERSION, FEATURE_VERSION, "1".repeat(64));
        jdbc.update("""
                INSERT INTO quant_strategy_version
                    (strategy_version, model_version, user_id, asset_id, product_type,
                     model_family, horizon_code, deployment_role, status,
                     validation_metrics_json, activated_at)
                VALUES (?, ?, 7, 12, 'STOCK', 'A_SHARE_STOCK', 'WAVE',
                        'CHAMPION', 'CHAMPION', '{}', CURRENT_TIMESTAMP)
                """, STRATEGY_VERSION, MODEL_VERSION);
        when(analysisServiceClient.quantJob(JOB_ID)).thenReturn(
                completedInferenceRemoteJob()
        );

        Map<String, Object> result = quantService.job(7L, JOB_ID);

        assertThat(result).containsEntry("status", "SUCCEEDED");
        assertThat(count("quant_prediction")).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT model_version, strategy_version
                FROM quant_prediction
                """))
                .containsEntry("MODEL_VERSION", MODEL_VERSION)
                .containsEntry("STRATEGY_VERSION", STRATEGY_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT profit_probability FROM quant_prediction",
                java.math.BigDecimal.class
        )).isEqualByComparingTo("0.68");
        assertThat(jdbc.queryForObject(
                "SELECT loss_probability FROM quant_prediction",
                java.math.BigDecimal.class
        )).isEqualByComparingTo("0.32");
        assertThat(jdbc.queryForObject(
                "SELECT expected_net_return FROM quant_prediction",
                java.math.BigDecimal.class
        )).isEqualByComparingTo("0.025");
        assertThat(count("quant_backtest_run")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void insertModel(String modelVersion, String lifecycle) {
        jdbc.update("""
                INSERT INTO quant_model_version
                    (model_version, feature_set_version, quant_config_version, product_type,
                     horizon_days, status, artifact_hash, metrics_json, trained_at)
                VALUES (?, ?, 'quant-research-v2', 'STOCK', 20, ?, ?, '{}', CURRENT_TIMESTAMP)
                """, modelVersion, FEATURE_VERSION, lifecycle, "9".repeat(64));
    }

    private void insertAllowedQuality() {
        jdbc.update("""
                INSERT INTO investment_data_quality_snapshot
                    (dataset_version, product_type, code, market, frequency, adjust_type,
                     provider, adapter_version, quality_config_version, quality_rule_set_version,
                     quality_status, decision, enforcement_mode, requested_start_date,
                     requested_end_date, sample_start_date, sample_end_date, fetched_at,
                     evaluated_at, manifest_json, report_json)
                VALUES (?, 'STOCK', '600519', 'SSE', 'DAILY', 'QFQ',
                        'TEST', 'test-v1', 'quality-v1', 'rules-v1',
                        'PASS', 'ALLOW', 'STRICT', DATE '2024-01-01',
                        DATE '2026-07-24', DATE '2024-01-01', DATE '2026-07-24',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}', '{}')
                """, DATASET_VERSION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castView(Object value) {
        return (Map<String, Object>) value;
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
        result.put("executionStatus", "COMPLETED");
        result.put("trainingOutcome", "VALIDATED");
        result.put("deploymentStatus", "RESEARCH");
        result.put("economicRole", "RETURN_ENHANCER");
        result.put("optimizationSummary", Map.of(
                "optimizationStudyId", "study-010736",
                "generation", 12
        ));
        result.put("baselineComparison", Map.of(
                "netExcessVsStrongestBaseline", 0.03
        ));
        result.put("diagnostics", List.of());
        result.put("validationReport", Map.of(
                "passed", true,
                "lifecycle", "VALIDATED",
                "failureCodes", List.of(),
                "checks", List.of()
        ));
        result.put("strategyVersion", STRATEGY_VERSION);
        result.put("asOfDate", "2026-07-24");
        result.put("profitProbability", 0.68);
        result.put("lossProbability", 0.32);
        result.put("expectedNetReturn", 0.025);
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
                "executionStatus", "COMPLETED",
                "trainingOutcome", "VALIDATED",
                "deploymentStatus", "RESEARCH",
                "economicRole", "RETURN_ENHANCER",
                "result", result
        );
    }

    private static Map<String, Object> completedInferenceRemoteJob() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("featureSetVersion", FEATURE_VERSION);
        result.put("featureArtifactUri", "quant-features/inference.parquet");
        result.put("featureArtifactHash", "f".repeat(64));
        result.put("featureSchema", List.of("momentum_primary", "realized_volatility"));
        result.put("quantConfigVersion", "quant-research-v2");
        result.put("modelFamily", "A_SHARE_STOCK");
        result.put("modelVersion", MODEL_VERSION);
        result.put("modelFileHash", "1".repeat(64));
        result.put("modelStatus", "PAPER_VERIFIED");
        result.put("strategyVersion", STRATEGY_VERSION);
        result.put("asOfDate", "2026-07-24");
        result.put("profitProbability", 0.68);
        result.put("lossProbability", 0.32);
        result.put("expectedNetReturn", 0.025);
        result.put("predictionInterval", List.of(-0.01, 0.06));
        result.put("confidence", "MEDIUM");
        result.put("action", "HOLD");
        result.put("targetWeight", 0.10);
        result.put("marketRegime", "UPTREND");
        result.put("benchmarkCode", "CSI300");
        result.put("roundTripCostBps", 18.0);
        result.put("featureVector", Map.of(
                "momentum_primary", 0.1,
                "realized_volatility", 0.2
        ));
        result.put("topFactors", List.of());
        result.put("riskFlags", List.of());
        return Map.of(
                "jobId", JOB_ID,
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
        result.put("trainingOutcome", "VALIDATION_FAILED");
        result.put("economicRole", "RISK_REFERENCE");
        result.put("validationReport", Map.of(
                "passed", false,
                "lifecycle", "DRAFT",
                "failureCodes", List.of("MODEL_REJECTED"),
                "checks", List.of()
        ));
        Map<String, Object> draftRemote = new LinkedHashMap<>(remote);
        draftRemote.put("trainingOutcome", "VALIDATION_FAILED");
        draftRemote.put("economicRole", "RISK_REFERENCE");
        return draftRemote;
    }
}
