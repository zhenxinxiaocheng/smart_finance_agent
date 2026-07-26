package com.smartfinance.agent.service;

import com.smartfinance.agent.investment.quant.PaperTradingService;
import com.smartfinance.agent.investment.quant.QuantPaperProperties;
import com.smartfinance.agent.investment.quant.QuantPrediction;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.QuantActionPlanService;
import com.smartfinance.agent.investment.quant.QuantModelManagementService;
import com.smartfinance.agent.investment.quant.QuantModelRegistryService;
import com.smartfinance.agent.investment.quant.QuantPredictionQueryService;
import com.smartfinance.agent.investment.quant.QuantService;
import com.smartfinance.agent.investment.quant.QuantServiceImpl;
import com.smartfinance.agent.investment.quant.QuantTrainingOrchestrator;
import com.smartfinance.agent.investment.quant.QuantTradingDecisionService;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
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

        quantService.job(7L, JOB_ID);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM quant_model_version WHERE model_version = ?",
                String.class,
                MODEL_VERSION
        )).isEqualTo("DRAFT");
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
        when(benchmarkProfileService.resolve(
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

        Map<String, Object> result = modelManagementService.management(7L, 12L);

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
    void repeatedAutomaticTrainingRequestReusesTheActiveSession() {
        String autoJobId = "6".repeat(32);
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
                "jobId", autoJobId,
                "status", "QUEUED",
                "configVersion", "quant-research-v2"
        ));

        Map<String, Object> first = trainingOrchestrator.refresh(7L, 12L, "WAVE");
        Map<String, Object> second = trainingOrchestrator.refresh(7L, 12L, "WAVE");

        assertThat(first).containsEntry("jobId", autoJobId);
        assertThat(second)
                .containsEntry("jobId", autoJobId)
                .containsEntry("status", "QUEUED");
        verify(analysisServiceClient, times(1)).createQuantJob(any());
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
