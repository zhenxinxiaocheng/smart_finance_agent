package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.map;
import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentProductDtos.*;

@Service
public class ExperimentProductService {
    private static final Set<String> STATUSES = Set.of(
            "QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");

    private final ExperimentService commands;
    private final ExperimentRepository repository;

    public ExperimentProductService(ExperimentService commands, ExperimentRepository repository) {
        this.commands = commands;
        this.repository = repository;
    }

    public ExperimentListItemResponse create(Long userId, CreateRequest request, String idempotencyKey) {
        requireText(idempotencyKey, 80, "IDEMPOTENCY_KEY_REQUIRED", "缺少有效的 Idempotency-Key");
        if (request == null) throw invalid("INVALID_EXPERIMENT_REQUEST", "参数敏感性请求不能为空");
        requireText(request.sourceBacktestId(), 36, "INVALID_EXPERIMENT_REQUEST", "请选择有效的来源回测");
        requireText(request.parameterKey(), 80, "INVALID_EXPERIMENT_REQUEST", "请选择有效的实验参数");
        requireText(request.name(), 160, "INVALID_EXPERIMENT_REQUEST", "请输入有效的实验名称");
        try {
            var created = commands.create(userId, request.sourceBacktestId(), request.parameterKey(),
                    request.name(), idempotencyKey);
            return listItem(repository.productExperiment(userId, created.id()));
        } catch (RestClientException exception) {
            throw new ExperimentTransportException(exception);
        }
    }

    public List<ExperimentListItemResponse> list(
            Long userId, String strategyId, String sourceBacktestId, String status) {
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus))
            throw invalid("INVALID_EXPERIMENT_STATUS", "实验状态筛选值无效");
        return repository.productExperiments(userId, blankToNull(strategyId),
                blankToNull(sourceBacktestId), normalizedStatus).stream().map(this::listItem).toList();
    }

    public ExperimentDetailResponse detail(Long userId, String experimentId) {
        var experiment = repository.productExperiment(userId, experimentId);
        var summary = map(experiment.get("summary"));
        var sourceContext = map(experiment.get("sourceContext"));
        var sourceProvenance = map(sourceContext.get("provenance"));
        var snapshot = new SnapshotResponse(
                text(experiment.get("snapshotId")), text(experiment.get("snapshotContentHash")),
                text(experiment.get("snapshotFormatVersion")), map(experiment.get("snapshotMetadata")));
        var provenance = new ProvenanceResponse(
                text(experiment.get("candidateRuleVersion")),
                text(experiment.get("stabilityAlgorithmVersion")),
                text(summary.get("evidenceSchemaVersion")), map(sourceContext.get("runtime")),
                map(experiment.get("environment")), snapshot, list(sourceContext.get("assumptions")),
                map(sourceContext.get("benchmarkContract")));
        var researchWindow = new ResearchWindowResponse(
                text(sourceProvenance.get("startDate")), text(sourceProvenance.get("endDate")));
        return new ExperimentDetailResponse(
                text(experiment.get("id")), text(experiment.get("name")), text(experiment.get("status")),
                integer(experiment.get("revision"), 0), progress(experiment.get("status")),
                parameter(experiment),
                new StrategyResponse(text(experiment.get("strategy_id")),
                        text(experiment.get("strategy_version_id")),
                        integerOrNull(experiment.get("strategyVersion")), text(experiment.get("strategyName"))),
                new SourceBacktestResponse(text(experiment.get("source_backtest_id")),
                        text(experiment.get("sourceName")), text(experiment.get("sourceStatus"))),
                researchWindow, summary(summary),
                repository.productRuns(userId, experimentId).stream().map(this::run).toList(),
                evidence(summary), provenance,
                text(experiment.get("created_at")), text(experiment.get("updated_at")),
                text(experiment.get("completed_at")));
    }

    public EligibilityResponse eligibility(Long userId, String sourceBacktestId) {
        requireText(sourceBacktestId, 36, "INVALID_EXPERIMENT_REQUEST", "请选择有效的来源回测");
        var source = repository.eligibilitySource(userId, sourceBacktestId);
        String reason = staticEligibilityReason(source);
        return reason == null
                ? new EligibilityResponse(true, null, null)
                : new EligibilityResponse(false, reason, eligibilityMessage(reason));
    }

    private String staticEligibilityReason(Map<String, Object> source) {
        if (!"backtests".equals(source.get("kind")) || Boolean.TRUE.equals(source.get("experimentTask")))
            return "SOURCE_NOT_FORMAL_BACKTEST";
        if (!"SUCCEEDED".equals(source.get("status"))) return "SOURCE_BACKTEST_NOT_SUCCEEDED";
        if (text(source.get("strategy_version_id")) == null) return "SOURCE_STRATEGY_VERSION_MISSING";
        var request = map(source.get("request"));
        var response = map(source.get("response"));
        var result = map(response.get("result"));
        var provenance = map(result.get("provenance"));
        if (!(request.get("assets") instanceof List<?> assets) || assets.isEmpty()
                || text(request.get("universeVersionId")) == null
                || map(provenance.get("config")).isEmpty()
                || !(result.get("assumptions") instanceof List<?>)) return "SOURCE_CONTEXT_INCOMPLETE";
        for (String key : List.of("startDate", "endDate", "engineVersion", "codeHash", "dataHash"))
            if (text(provenance.get(key)) == null) return "SOURCE_CONTEXT_INCOMPLETE";
        if (ExperimentInvariant.benchmark(result.get("benchmark")).isEmpty()) return "SOURCE_CONTEXT_INCOMPLETE";
        return null;
    }

    private ExperimentListItemResponse listItem(Map<String, Object> experiment) {
        var summary = map(experiment.get("summary"));
        return new ExperimentListItemResponse(
                text(experiment.get("id")), text(experiment.get("name")), text(experiment.get("status")),
                text(experiment.get("source_backtest_id")), text(experiment.get("strategy_id")),
                text(experiment.get("strategy_version_id")), parameter(experiment),
                text(summary.get("classification")), text(summary.get("performanceProfile")),
                text(summary.get("evidenceQuality")), progress(experiment.get("status")),
                integer(experiment.get("revision"), 0), text(experiment.get("created_at")),
                text(experiment.get("updated_at")), text(experiment.get("completed_at")));
    }

    private ParameterResponse parameter(Map<String, Object> experiment) {
        var definition = map(experiment.get("variableDefinition"));
        String key = text(definition.get("key"));
        var baseline = map(experiment.get("baselineValues"));
        return new ParameterResponse(key, key == null ? null : baseline.get(key), list(experiment.get("candidateValues")));
    }

    private ExperimentSummaryResponse summary(Map<String, Object> value) {
        if (value.isEmpty()) return null;
        var qualification = map(value.get("qualificationSummary"));
        QualificationSummaryResponse qualificationResponse = qualification.isEmpty() ? null
                : new QualificationSummaryResponse(integerOrNull(qualification.get("qualified")),
                integerOrNull(qualification.get("unqualified")), integerOrNull(qualification.get("missing")),
                map(qualification.get("reasons")));
        return new ExperimentSummaryResponse(
                text(value.get("classification")), text(value.get("performanceProfile")), list(value.get("stableRange")),
                text(value.get("direction")), value.get("directionConsistency"), number(value.get("returnTolerance")),
                number(value.get("drawdownTolerance")), nullableMap(value.get("localSensitivity")),
                booleanOrNull(value.get("isolatedPeak")), strings(value.get("reasonCodes")), qualificationResponse);
    }

    private EvidenceResponse evidence(Map<String, Object> summary) {
        var value = map(summary.get("evidence"));
        if (value.isEmpty() && summary.get("evidenceQuality") == null) return null;
        return new EvidenceResponse(text(summary.get("evidenceQuality")), text(value.get("controlIntegrity")),
                text(value.get("dataConsistency")), text(value.get("sourceCompleteness")),
                text(value.get("runCoverage")), integerOrNull(value.get("validRuns")),
                integerOrNull(value.get("totalRuns")), integerOrNull(value.get("excludedRunCount")),
                map(value.get("reasonClassifications")));
    }

    private RunResponse run(Map<String, Object> row) {
        var response = map(row.get("result"));
        var qualification = map(response.get("qualification"));
        var result = map(response.get("result"));
        var metrics = map(result.get("metrics"));
        var validation = map(response.get("experimentValidation"));
        var values = map(row.get("variableValues"));
        var errorCode = text(row.get("errorCode"));
        return new RunResponse(text(row.get("id")), integer(row.get("ordinal"), 0),
                values.isEmpty() ? null : values.values().iterator().next(), Boolean.TRUE.equals(row.get("baseline")),
                text(row.get("status")), text(row.get("stage")), progress(row.get("status")),
                text(row.get("attemptId")), qualification.isEmpty() ? null
                : new QualificationResponse(text(qualification.get("status")), strings(qualification.get("reasons")),
                text(qualification.get("scope"))),
                metrics.isEmpty() ? null : new MetricsResponse(number(metrics.get("netReturn")),
                number(metrics.get("maxDrawdown")), number(metrics.get("volatility")),
                number(metrics.get("turnover")), number(metrics.get("tradeCount"))),
                validation.isEmpty() ? null : new ValidationResponse(booleanOrNull(validation.get("valid")),
                text(validation.get("code")), list(validation.get("mismatches")),
                text(validation.get("validatorVersion"))),
                errorCode == null ? null : new RunErrorResponse(errorCode, "该次运行未成功完成"),
                text(row.get("updatedAt")));
    }

    private static ExperimentInvariant.ExperimentException invalid(String reasonCode, String message) {
        return new ExperimentInvariant.ExperimentException("INVALID_EXPERIMENT_REQUEST", reasonCode, message);
    }

    private static void requireText(String value, int maximum, String reasonCode, String message) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid(reasonCode, message);
    }

    private static String eligibilityMessage(String reason) {
        return switch (reason) {
            case "SOURCE_NOT_FORMAL_BACKTEST" -> "请选择正式回测结果";
            case "SOURCE_BACKTEST_NOT_SUCCEEDED" -> "来源回测尚未成功完成";
            case "SOURCE_STRATEGY_VERSION_MISSING" -> "来源回测缺少策略版本";
            default -> "来源回测缺少参数敏感性检查所需的冻结研究信息";
        };
    }

    private static Integer progress(Object status) { return "SUCCEEDED".equals(status) ? 100 : null; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static Integer integerOrNull(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private static Number number(Object value) { return value instanceof Number n ? n : null; }
    private static Boolean booleanOrNull(Object value) { return value instanceof Boolean b ? b : null; }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    private static Map<String, Object> nullableMap(Object value) {
        var result = map(value);
        return result.isEmpty() && value == null ? null : result;
    }
}
