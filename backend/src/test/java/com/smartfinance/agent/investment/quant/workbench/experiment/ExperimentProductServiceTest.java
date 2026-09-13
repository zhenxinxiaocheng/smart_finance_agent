package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.encode;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExperimentProductServiceTest {
    private JdbcTemplate db;
    private ExperimentRepository repository;
    private ExperimentProductService products;
    private WorkbenchAnalysisClient client;
    private ObjectMapper json;
    private String sourceId;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/sqlite/V30__quant_strategy_workbench.sql"),
                new ClassPathResource("db/migration/sqlite/V33__quant_parameter_sensitivity_experiments.sql"))
                .execute(source);
        json = new ObjectMapper();
        repository = new ExperimentRepository(db, json);
        var manager = new DataSourceTransactionManager(source);
        var snapshots = new ResearchSnapshotStore(db, json, 1_000_000, 1_000_000);
        var tasks = new ExperimentTaskStore(db, json);
        var attempts = new ExperimentAttemptService(db, repository, tasks, manager);
        client = mock(WorkbenchAnalysisClient.class);
        var runtime = Map.<String, Object>of(
                "engineVersion", "current", "codeHash", "current-code",
                "parameterCatalogVersion", "catalog", "candidateRuleVersion", "candidate-v1");
        when(client.runtimeInfo()).thenReturn(runtime);
        when(client.validateConfig(anyMap())).thenAnswer(call -> Map.of(
                "compatible", true, "runtime", runtime,
                "effectiveConfig", map(call.getArgument(0)).get("config"),
                "assumptions", List.of("fixed assumptions"),
                "benchmarkContract", Map.of("status", "READY", "type", "UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE")));
        when(client.candidates(anyMap())).thenReturn(Map.of(
                "parameterKey", "slowWindow", "baseline", 60, "delta", 6,
                "values", List.of(48, 54, 60, 66, 72), "ruleVersion", "candidate-v1"));

        db.update("INSERT INTO quant_v2_object VALUES('strategy',7,'strategies','趋势策略','ACTIVE',1,'{}','now','now')");
        db.update("INSERT INTO quant_v2_object VALUES('universe',7,'universes','研究池','ACTIVE',1,'{}','now','now')");
        db.update("INSERT INTO quant_v2_version VALUES('strategy-v1',7,'strategy',1,'{}','now')");
        db.update("INSERT INTO quant_v2_version VALUES('universe-v1',7,'universe',1,'{}','now')");

        var original = new LinkedHashMap<String, Object>();
        original.put("kind", "BACKTEST");
        original.put("strategyVersionId", "strategy-v1");
        original.put("universeId", "universe");
        original.put("universeVersionId", "universe-v1");
        original.put("startDate", "2024-01-01");
        original.put("endDate", "2024-12-31");
        original.put("config", Map.of("slowWindow", 60, "feeRate", .001));
        original.put("assets", List.of(Map.of(
                "id", "asset", "code", "000001", "name", "样本资产", "assetClass", "STOCK",
                "bars", List.of(Map.of("date", "2024-01-01", "close", 10)))));
        sourceId = tasks.enqueueBacktest(7L, "正式回测", "strategy", "strategy-v1", "universe", original);
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',stage='COMPLETED',result_json=? WHERE id=?",
                encode(sourceResponse(original)), sourceId);
        var commands = new ExperimentService(db, repository, snapshots,
                new ExperimentCandidateService(client), client, attempts, manager);
        products = new ExperimentProductService(commands, repository);
    }

    @Test
    void createIsIdempotentAndListIsLightFilteredAndUserScoped() throws Exception {
        var request = new ExperimentProductDtos.CreateRequest(sourceId, "slowWindow", "慢均线参数敏感性");
        var first = products.create(7L, request, "request-key");
        var second = products.create(7L, request, "request-key");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(products.list(7L, "strategy", sourceId, "QUEUED")).extracting("id").containsExactly(first.id());
        assertThat(products.list(7L, "other", null, null)).isEmpty();
        assertThat(products.list(8L, null, null, null)).isEmpty();

        String serialized = json.writeValueAsString(products.list(7L, null, null, null));
        assertThat(serialized).contains("classification", "performanceProfile", "evidenceQuality");
        assertThat(serialized).doesNotContain("sourceContext", "history", "excludedRuns", "attemptId", "requestKey");
        assertThatThrownBy(() -> products.create(7L,
                new ExperimentProductDtos.CreateRequest(sourceId, "slowWindow", "其他名称"), "request-key"))
                .isInstanceOf(ExperimentInvariant.ExperimentException.class)
                .hasMessage("EXPERIMENT_REQUEST_CONFLICT");
    }

    @Test
    void detailUsesActiveTasksAndKeepsExecutionQualificationEvidenceAndVersionsSeparate() throws Exception {
        var created = products.create(7L,
                new ExperimentProductDtos.CreateRequest(sourceId, "slowWindow", "慢均线参数敏感性"), "detail-key");
        var runs = repository.runs(7L, created.id());
        var succeededTask = repository.attempt(7L, runs.get(0).activeAttemptId()).taskId();
        var failedTask = repository.attempt(7L, runs.get(1).activeAttemptId()).taskId();
        db.update("UPDATE quant_v2_task SET status='SUCCEEDED',stage='COMPLETED',result_json=?,updated_at='later' WHERE id=?",
                encode(runResponse()), succeededTask);
        db.update("UPDATE quant_v2_task SET status='FAILED',stage='FAILED',error_code='NO_EXECUTED_TRADES',error_message='private stack trace',updated_at='later' WHERE id=?",
                failedTask);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("classification", "STABLE");
        summary.put("performanceProfile", "NEGATIVE");
        summary.put("stableRange", List.of(54, 66));
        summary.put("direction", "FLAT");
        summary.put("directionConsistency", .9);
        summary.put("returnTolerance", .02);
        summary.put("drawdownTolerance", .03);
        summary.put("localSensitivity", Map.of("return", .01, "drawdown", .01));
        summary.put("isolatedPeak", false);
        summary.put("reasonCodes", List.of("DRAWDOWN_LIMIT_EXCEEDED"));
        summary.put("qualificationSummary", Map.of("qualified", 0, "unqualified", 1, "missing", 4,
                "reasons", Map.of("DRAWDOWN_LIMIT_EXCEEDED", 1)));
        summary.put("evidenceSchemaVersion", "experiment-evidence-v2");
        summary.put("evidenceQuality", "HIGH");
        summary.put("evidence", Map.of(
                "controlIntegrity", "PASS", "dataConsistency", "PASS", "sourceCompleteness", "COMPLETE",
                "runCoverage", "COMPLETE", "validRuns", 5, "totalRuns", 5, "excludedRunCount", 0,
                "excludedRuns", List.of(Map.of("attemptId", "must-not-leak")),
                "reasonClassifications", Map.of("DRAWDOWN_LIMIT_EXCEEDED", "RUN_QUALIFICATION")));
        summary.put("history", List.of(Map.of("inputAttemptIds", List.of("old-attempt"))));
        db.update("UPDATE quant_v2_experiment SET status='SUCCEEDED',summary_json=?,revision=3,completed_at='done' WHERE id=?",
                encode(summary), created.id());

        clearInvocations(client);
        int revisionBefore = db.queryForObject("SELECT revision FROM quant_v2_experiment WHERE id=?", Integer.class, created.id());
        var detail = products.detail(7L, created.id());
        assertThat(detail.runs()).hasSize(5);
        assertThat(detail.runs().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(detail.runs().get(0).qualification().status()).isEqualTo("UNQUALIFIED");
        assertThat(detail.runs().get(0).progress()).isEqualTo(100);
        assertThat(detail.runs().get(1).status()).isEqualTo("FAILED");
        assertThat(detail.runs().get(1).error().code()).isEqualTo("NO_EXECUTED_TRADES");
        assertThat(detail.summary().directionConsistency()).isEqualTo(.9);
        assertThat(detail.evidence().quality()).isEqualTo("HIGH");
        assertThat(detail.provenance().stabilityAlgorithmVersion()).isEqualTo("parameter-stability-v2");
        assertThat(detail.provenance().evidenceSchemaVersion()).isEqualTo("experiment-evidence-v2");
        assertThat(detail.provenance().sourceRuntime()).containsEntry("engineVersion", "source-engine");
        assertThat(detail.provenance().experimentRuntime()).containsEntry("engineVersion", "current");
        assertThat(detail.provenance().snapshot().metadata()).containsKey("assets");
        assertThat(db.queryForObject("SELECT revision FROM quant_v2_experiment WHERE id=?", Integer.class, created.id()))
                .isEqualTo(revisionBefore);
        verifyNoInteractions(client);

        String serialized = json.writeValueAsString(detail);
        assertThat(serialized).doesNotContain("private stack trace", "must-not-leak", "old-attempt", "bars",
                "requestKey", "requestHash", "invariantHash", "baseRequest", "sourceContext", "valueHash");
        assertThatThrownBy(() -> products.detail(8L, created.id()))
                .isInstanceOf(ExperimentRepository.ExperimentNotFoundException.class);
    }

    @Test
    void legacyV1DirectionValueIsPreservedWithoutInventingV2Direction() {
        var created = products.create(7L,
                new ExperimentProductDtos.CreateRequest(sourceId, "slowWindow", "旧实验"), "legacy-key");
        db.update("UPDATE quant_v2_experiment SET stability_algorithm_version='parameter-stability-v1',summary_json=? WHERE id=?",
                encode(Map.of("classification", "STABLE", "performanceProfile", "POSITIVE",
                        "stableRange", List.of(54, 66), "directionConsistency", "INCREASING",
                        "reasonCodes", List.of())), created.id());
        var detail = products.detail(7L, created.id());
        assertThat(detail.summary().direction()).isNull();
        assertThat(detail.summary().directionConsistency()).isEqualTo("INCREASING");
        assertThat(detail.provenance().stabilityAlgorithmVersion()).isEqualTo("parameter-stability-v1");
    }

    @Test
    void eligibilityIsStaticAndDoesNotContactAnalysisService() {
        clearInvocations(client);
        assertThat(products.eligibility(7L, sourceId).eligible()).isTrue();
        verifyNoInteractions(client);
        db.update("UPDATE quant_v2_task SET status='FAILED' WHERE id=?", sourceId);
        var result = products.eligibility(7L, sourceId);
        assertThat(result.eligible()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("SOURCE_BACKTEST_NOT_SUCCEEDED");
        verifyNoInteractions(client);
    }

    private Map<String, Object> sourceResponse(Map<String, Object> request) {
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("engineVersion", "source-engine");
        provenance.put("codeHash", "source-code");
        provenance.put("dataHash", "source-data");
        provenance.put("config", request.get("config"));
        provenance.put("strategyVersionId", "strategy-v1");
        provenance.put("universeId", "universe");
        provenance.put("universeVersion", "universe-v1");
        provenance.put("factorSetVersionId", null);
        provenance.put("startDate", "2024-01-01");
        provenance.put("endDate", "2024-12-31");
        var result = new LinkedHashMap<String, Object>();
        result.put("provenance", provenance);
        result.put("assumptions", List.of("fixed assumptions"));
        result.put("benchmark", Map.of("status", "READY", "type", "UNIVERSE_EQUAL_WEIGHT_MATCHED_EXPOSURE"));
        return Map.of("status", "SUCCEEDED", "result", result);
    }

    private Map<String, Object> runResponse() {
        return Map.of(
                "qualification", Map.of("status", "UNQUALIFIED", "reasons", List.of("DRAWDOWN_LIMIT_EXCEEDED")),
                "experimentValidation", Map.of("valid", true),
                "result", Map.of("metrics", Map.of(
                        "netReturn", -.1, "maxDrawdown", .12, "volatility", .2,
                        "turnover", .3, "tradeCount", 7)));
    }
}
