package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class QuantResearchServiceImpl implements QuantResearchService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> MODEL_FAMILIES = Set.of(
            "A_SHARE_STOCK",
            "INDEX_FUND",
            "ACTIVE_FUND",
            "QDII_INDEX_FUND",
            "COMMODITY_FUND"
    );
    private static final Set<String> ALGORITHMS = Set.of(
            "ELASTIC_NET",
            "XGBOOST",
            "EXTRA_TREES",
            "TREND_VOLATILITY",
            "RISK_FILTERED_MEAN_REVERSION",
            "REGIME_ENSEMBLE",
            // One compatibility cycle for persisted v1 experiments.
            "GRADIENT_BOOSTING",
            "VALIDATED_ENSEMBLE"
    );
    private static final Set<String> TERMINAL_EXPERIMENT_STATUSES =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final BenchmarkProfileMapper benchmarkMapper;
    private final QuantExperimentMapper experimentMapper;
    private final QuantValidationReportMapper reportMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantStrategyVersionMapper strategyMapper;
    private final QuantResearchUniverseMapper universeMapper;
    private final QuantUniverseMembershipMapper membershipMapper;
    private final QuantService quantService;
    private final ObjectMapper objectMapper;

    public QuantResearchServiceImpl(
            BenchmarkProfileMapper benchmarkMapper,
            QuantExperimentMapper experimentMapper,
            QuantValidationReportMapper reportMapper,
            QuantModelVersionMapper modelMapper,
            QuantStrategyVersionMapper strategyMapper,
            QuantResearchUniverseMapper universeMapper,
            QuantUniverseMembershipMapper membershipMapper,
            QuantService quantService,
            ObjectMapper objectMapper
    ) {
        this.benchmarkMapper = benchmarkMapper;
        this.experimentMapper = experimentMapper;
        this.reportMapper = reportMapper;
        this.modelMapper = modelMapper;
        this.strategyMapper = strategyMapper;
        this.universeMapper = universeMapper;
        this.membershipMapper = membershipMapper;
        this.quantService = quantService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> modelFamilies() {
        return QuantResearchCatalog.modelFamilies();
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return QuantResearchCatalog.parameterSchema();
    }

    @Override
    public List<Map<String, Object>> benchmarks() {
        List<BenchmarkProfile> profiles = benchmarkMapper.selectList(
                new LambdaQueryWrapper<BenchmarkProfile>()
                        .eq(BenchmarkProfile::getActive, true)
                        .orderByAsc(BenchmarkProfile::getProductType)
                        .orderByAsc(BenchmarkProfile::getProductCode)
                        .orderByDesc(BenchmarkProfile::getEffectiveFrom)
        );
        return profiles.stream().map(this::benchmarkView).toList();
    }

    @Override
    public List<Map<String, Object>> researchUniverses() {
        return universeMapper.selectList(
                new LambdaQueryWrapper<QuantResearchUniverse>()
                        .eq(QuantResearchUniverse::getActive, true)
                        .orderByAsc(QuantResearchUniverse::getModelFamily)
                        .orderByAsc(QuantResearchUniverse::getName)
        ).stream().map(this::universeView).toList();
    }

    @Override
    @Transactional
    public Map<String, Object> createExperiment(
            Long userId,
            QuantResearchController.ExperimentRequest request
    ) {
        String family = normalizeFamily(request.modelFamily());
        String horizon = normalizeHorizon(request.horizonCode());
        String algorithm = normalizeAlgorithm(request.algorithm());
        validateParameters(request.parameters());
        QuantResearchUniverse universe = resolveUniverse(request.universeId(), family);
        String universeVersion = quantService.researchContextVersion(
                userId,
                request.assetId(),
                universe == null ? null : universe.getId()
        );
        String fingerprint = QuantResearchCatalog.experimentFingerprint(
                userId,
                request.assetId(),
                universe == null ? null : universe.getId(),
                universeVersion,
                family,
                horizon,
                algorithm,
                request.parameters()
        );
        QuantExperiment existing = experimentMapper.selectOne(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, userId)
                        .eq(QuantExperiment::getExperimentFingerprint, fingerprint)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            Map<String, Object> reused = new LinkedHashMap<>(experimentView(existing));
            reused.put("reused", true);
            return reused;
        }

        QuantExperiment experiment = new QuantExperiment();
        experiment.setUserId(userId);
        experiment.setAssetId(request.assetId());
        experiment.setUniverseId(universe == null ? null : universe.getId());
        experiment.setModelFamily(family);
        experiment.setHorizonCode(horizon);
        experiment.setHorizonDays(horizonDays(horizon));
        experiment.setStatus("QUEUED");
        experiment.setExecutionStatus("QUEUED");
        experiment.setTrainingOutcome("OPTIMIZING");
        experiment.setDeploymentStatus("RESEARCH");
        experiment.setTrainingMode("TUNE");
        experiment.setTriggerReason("USER_TUNING");
        experiment.setSearchSummaryJson(writeJson(Map.of()));
        experiment.setExperimentFingerprint(fingerprint);
        experiment.setConfigJson(writeJson(Map.of(
                "algorithm", algorithm,
                "parameters", request.parameters()
        )));
        experiment.setQuantConfigVersion("quant-research-v2");
        experiment.setCodeVersion("quant-research-lab-v1");
        experimentMapper.insert(experiment);

        try {
            Map<String, Object> job = quantService.refreshExperiment(
                    userId,
                    request.assetId(),
                    universe == null ? null : universe.getId(),
                    horizon,
                    fingerprint,
                    family,
                    algorithm,
                    request.parameters()
            );
            experiment.setStatus(String.valueOf(job.getOrDefault("status", "QUEUED")));
            experiment.setExecutionStatus(text(job.get("executionStatus")));
            experiment.setTrainingOutcome(text(job.get("trainingOutcome")));
            experiment.setDeploymentStatus(text(job.get("deploymentStatus")));
            experiment.setEconomicRole(text(job.get("economicRole")));
            experiment.setErrorCode(text(job.get("errorCode")));
            experiment.setErrorSummary(text(job.get("errorSummary")));
            Map<String, Object> logs = new LinkedHashMap<>();
            logs.put("jobId", job.get("jobId"));
            logs.put("jobStatus", experiment.getStatus());
            experiment.setLogsJson(writeJson(logs));
            experiment.setStartedAt(LocalDateTime.now());
        } catch (RuntimeException exception) {
            experiment.setStatus("FAILED");
            experiment.setErrorCode("JOB_FAILED");
            experiment.setErrorSummary(errorSummary(exception));
            experiment.setFinishedAt(LocalDateTime.now());
        }
        experimentMapper.updateById(experiment);
        return experimentView(experiment);
    }

    @Override
    public List<Map<String, Object>> experiments(Long userId) {
        return experimentMapper.selectList(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, userId)
                        .orderByDesc(QuantExperiment::getCreatedAt)
                        .last("LIMIT 100")
        ).stream().map(this::experimentView).toList();
    }

    @Override
    public Map<String, Object> experiment(Long userId, Long experimentId) {
        QuantExperiment experiment = requireExperiment(userId, experimentId);
        if (Set.of("QUEUED", "RUNNING").contains(experiment.getStatus())) {
            String jobId = text(readJson(experiment.getLogsJson()).get("jobId"));
            if (jobId != null) {
                try {
                    quantService.job(userId, jobId);
                    experiment = requireExperiment(userId, experimentId);
                } catch (RuntimeException ignored) {
                    // Polling failures are transient; the stored job state remains authoritative.
                }
            }
        }
        return experimentView(experiment);
    }

    @Override
    @Transactional
    public Map<String, Object> cancelExperiment(Long userId, Long experimentId) {
        QuantExperiment experiment = requireExperiment(userId, experimentId);
        if (!TERMINAL_EXPERIMENT_STATUSES.contains(experiment.getStatus())) {
            String jobId = text(readJson(experiment.getLogsJson()).get("jobId"));
            if (jobId != null) {
                quantService.cancelJob(userId, jobId);
                experiment = requireExperiment(userId, experimentId);
            } else {
                experiment.setStatus("CANCELLED");
                experiment.setFinishedAt(LocalDateTime.now());
                experimentMapper.updateById(experiment);
            }
        }
        return experimentView(experiment);
    }

    @Override
    @Transactional
    public Map<String, Object> promoteExperiment(Long userId, Long experimentId) {
        QuantExperiment experiment = requireExperiment(userId, experimentId);
        QuantValidationReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<QuantValidationReport>()
                        .eq(QuantValidationReport::getExperimentId, experimentId)
                        .orderByDesc(QuantValidationReport::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (report == null || !Boolean.TRUE.equals(report.getPassed())) {
            throw new IllegalStateException("实验未通过严格验证，不能晋级模拟盘");
        }
        QuantModelVersion model = modelMapper.selectOne(
                new LambdaQueryWrapper<QuantModelVersion>()
                        .eq(QuantModelVersion::getModelVersion, report.getModelVersion())
                        .last("LIMIT 1")
        );
        if (model == null) {
            throw new IllegalStateException("实验模型不存在");
        }
        model.setStatus("VALIDATED");
        modelMapper.updateById(model);
        QuantStrategyVersion strategy = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getModelVersion, model.getModelVersion())
                        .last("LIMIT 1")
        );
        if (strategy != null) {
            strategy.setStatus("PAPER");
            strategy.setActivatedAt(LocalDateTime.now());
            strategyMapper.updateById(strategy);
        }
        quantService.activatePaperModel(userId, model.getModelVersion());
        experiment.setStatus("SUCCEEDED");
        experiment.setCandidateModelVersion(model.getModelVersion());
        experiment.setFinishedAt(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        return experimentView(experiment);
    }

    @Override
    public Map<String, Object> dataQuality(Long userId) {
        List<QuantExperiment> experiments = experimentMapper.selectList(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, userId)
                        .orderByDesc(QuantExperiment::getCreatedAt)
                        .last("LIMIT 50")
        );
        long failed = experiments.stream()
                .filter(item -> "FAILED".equals(item.getStatus()))
                .count();
        long running = experiments.stream()
                .filter(item -> Set.of("QUEUED", "RUNNING").contains(item.getStatus()))
                .count();
        return Map.of(
                "validationMode", "STRICT",
                "experimentCount", experiments.size(),
                "runningCount", running,
                "failedCount", failed,
                "benchmarkProfileCount", benchmarkMapper.selectCount(
                        new LambdaQueryWrapper<BenchmarkProfile>()
                                .eq(BenchmarkProfile::getActive, true)
                )
        );
    }

    @Override
    public Map<String, Object> paperStrategy(Long userId, Long strategyId) {
        QuantStrategyVersion strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) {
            throw new IllegalArgumentException("模拟策略不存在");
        }
        long ownership = experimentMapper.selectCount(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, userId)
                        .eq(QuantExperiment::getCandidateModelVersion, strategy.getModelVersion())
        );
        if (ownership == 0) {
            throw new IllegalArgumentException("模拟策略不存在");
        }
        return Map.of(
                "id", strategy.getId(),
                "strategyVersion", strategy.getStrategyVersion(),
                "modelVersion", strategy.getModelVersion(),
                "status", strategy.getStatus(),
                "validation", readJson(strategy.getValidationMetricsJson())
        );
    }

    private QuantExperiment requireExperiment(Long userId, Long experimentId) {
        QuantExperiment experiment = experimentMapper.selectOne(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getId, experimentId)
                        .eq(QuantExperiment::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (experiment == null) {
            throw new IllegalArgumentException("量化实验不存在");
        }
        return experiment;
    }

    private Map<String, Object> experimentView(QuantExperiment experiment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", experiment.getId());
        result.put("assetId", experiment.getAssetId());
        result.put("universeId", experiment.getUniverseId());
        result.put("modelFamily", experiment.getModelFamily());
        result.put("horizonCode", experiment.getHorizonCode());
        result.put("horizonDays", experiment.getHorizonDays());
        result.put("status", experiment.getStatus());
        result.put("executionStatus", experiment.getExecutionStatus());
        result.put("trainingOutcome", experiment.getTrainingOutcome());
        result.put("deploymentStatus", experiment.getDeploymentStatus());
        result.put("economicRole", experiment.getEconomicRole());
        result.put("optimizationStudyId", experiment.getOptimizationStudyId());
        result.put("optimizationGeneration", experiment.getOptimizationGeneration());
        result.put("baselineComparison", readJson(
                experiment.getBaselineComparisonJson()
        ));
        result.put("trainingMode", experiment.getTrainingMode());
        result.put("triggerReason", experiment.getTriggerReason());
        result.put("parentModelVersion", experiment.getParentModelVersion());
        result.put("bestModelVersion", experiment.getBestModelVersion());
        result.put("searchSummary", readJson(experiment.getSearchSummaryJson()));
        result.put("experimentFingerprint", experiment.getExperimentFingerprint());
        Map<String, Object> config = readJson(experiment.getConfigJson());
        result.put("algorithm", config.getOrDefault("algorithm", "VALIDATED_ENSEMBLE"));
        result.put("parameters", config.get("parameters") instanceof Map<?, ?>
                ? config.get("parameters")
                : config);
        result.put("datasetVersion", experiment.getDatasetVersion());
        result.put("featureSetVersion", experiment.getFeatureSetVersion());
        result.put("quantConfigVersion", experiment.getQuantConfigVersion());
        result.put("codeVersion", experiment.getCodeVersion());
        result.put("candidateModelVersion", experiment.getCandidateModelVersion());
        result.put("logs", readJson(experiment.getLogsJson()));
        result.put("errorCode", experiment.getErrorCode());
        result.put("errorSummary", experiment.getErrorSummary());
        result.put("startedAt", experiment.getStartedAt());
        result.put("finishedAt", experiment.getFinishedAt());
        QuantValidationReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<QuantValidationReport>()
                        .eq(QuantValidationReport::getExperimentId, experiment.getId())
                        .orderByDesc(QuantValidationReport::getCreatedAt)
                        .last("LIMIT 1")
        );
        result.put("validationReport", report == null ? null : validationReportView(report));
        result.put("comparison", comparisonView(experiment, report));
        result.put("reused", false);
        return result;
    }

    private Map<String, Object> comparisonView(
            QuantExperiment current,
            QuantValidationReport currentReport
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", Map.of(
                "datasetVersion", Objects.toString(current.getDatasetVersion(), ""),
                "horizonCode", Objects.toString(current.getHorizonCode(), ""),
                "validationMethod", Objects.toString(
                        current.getQuantConfigVersion(),
                        ""
                )
        ));
        QuantExperiment previous = previousComparable(current);
        if (previous == null) {
            result.put("comparable", false);
            result.put("reason", "NO_COMPATIBLE_PREVIOUS_EXPERIMENT");
            result.put("parameterChanges", List.of());
            return result;
        }

        QuantValidationReport previousReport = reportMapper.selectOne(
                new LambdaQueryWrapper<QuantValidationReport>()
                        .eq(
                                QuantValidationReport::getExperimentId,
                                previous.getId()
                        )
                        .orderByDesc(QuantValidationReport::getCreatedAt)
                        .last("LIMIT 1")
        );
        Map<String, Object> currentConfig = readJson(current.getConfigJson());
        Map<String, Object> previousConfig = readJson(previous.getConfigJson());
        Map<String, Object> currentParameters = childMap(
                currentConfig,
                "parameters"
        );
        Map<String, Object> previousParameters = childMap(
                previousConfig,
                "parameters"
        );
        List<Map<String, Object>> parameterChanges = new ArrayList<>();
        Set<String> parameterKeys = new LinkedHashSet<>();
        parameterKeys.addAll(previousParameters.keySet());
        parameterKeys.addAll(currentParameters.keySet());
        for (String key : parameterKeys) {
            Object before = previousParameters.get(key);
            Object after = currentParameters.get(key);
            if (!Objects.equals(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("parameter", key);
                change.put("before", before);
                change.put("after", after);
                parameterChanges.add(change);
            }
        }
        if (!Objects.equals(
                previousConfig.get("algorithm"),
                currentConfig.get("algorithm")
        )) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("parameter", "algorithm");
            change.put("before", previousConfig.get("algorithm"));
            change.put("after", currentConfig.get("algorithm"));
            parameterChanges.add(0, change);
        }

        Map<String, Object> currentBaseline = readJson(
                current.getBaselineComparisonJson()
        );
        Map<String, Object> previousBaseline = readJson(
                previous.getBaselineComparisonJson()
        );
        Map<String, Object> currentMetrics = currentReport == null
                ? Map.of()
                : readJson(currentReport.getMetricsJson());
        Map<String, Object> previousMetrics = previousReport == null
                ? Map.of()
                : readJson(previousReport.getMetricsJson());
        Double currentExcess = numeric(
                currentBaseline.get("netExcessVsStrongestBaseline")
        );
        Double previousExcess = numeric(
                previousBaseline.get("netExcessVsStrongestBaseline")
        );
        Double currentDrawdown = numeric(currentMetrics.get("maximumDrawdown"));
        Double previousDrawdown = numeric(previousMetrics.get("maximumDrawdown"));
        Map<String, Object> searchSummary = readJson(
                current.getSearchSummaryJson()
        );

        result.put("comparable", true);
        result.put("previousExperimentId", previous.getId());
        result.put("parameterChanges", parameterChanges);
        result.put("netExcessVsStrongestBaseline", currentExcess);
        result.put("netExcessChange", difference(currentExcess, previousExcess));
        result.put(
                "drawdownImprovement",
                drawdownImprovement(currentDrawdown, previousDrawdown)
        );
        result.put("bottleneck", bottleneck(current, searchSummary));
        result.put("nextSuggestion", text(searchSummary.get("nextAdjustment")));
        return result;
    }

    private QuantExperiment previousComparable(QuantExperiment current) {
        if (current.getId() == null
                || current.getDatasetVersion() == null
                || current.getDatasetVersion().isBlank()
                || current.getHorizonCode() == null
                || current.getQuantConfigVersion() == null
                || current.getQuantConfigVersion().isBlank()) {
            return null;
        }
        return experimentMapper.selectOne(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, current.getUserId())
                        .eq(QuantExperiment::getAssetId, current.getAssetId())
                        .eq(
                                QuantExperiment::getDatasetVersion,
                                current.getDatasetVersion()
                        )
                        .eq(
                                QuantExperiment::getHorizonCode,
                                current.getHorizonCode()
                        )
                        .eq(
                                QuantExperiment::getQuantConfigVersion,
                                current.getQuantConfigVersion()
                        )
                        .lt(QuantExperiment::getId, current.getId())
                        .orderByDesc(QuantExperiment::getId)
                        .last("LIMIT 1")
        );
    }

    private static Map<String, Object> childMap(
            Map<String, Object> source,
            String key
    ) {
        Object raw = source.get(key);
        if (!(raw instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((childKey, value) ->
                result.put(String.valueOf(childKey), value));
        return result;
    }

    private static Map<String, Object> bottleneck(
            QuantExperiment experiment,
            Map<String, Object> searchSummary
    ) {
        Object raw = searchSummary.get("failureDiagnosis");
        if (raw instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) ->
                    result.put(String.valueOf(key), value));
            return result;
        }
        if (experiment.getErrorCode() == null) {
            return Map.of();
        }
        return Map.of(
                "code", experiment.getErrorCode(),
                "message", Objects.toString(experiment.getErrorSummary(), "")
        );
    }

    private static Double difference(Double current, Double previous) {
        return current == null || previous == null ? null : current - previous;
    }

    private static Double drawdownImprovement(
            Double current,
            Double previous
    ) {
        return current == null || previous == null
                ? null
                : Math.abs(previous) - Math.abs(current);
    }

    private static Double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> universeView(QuantResearchUniverse universe) {
        List<QuantUniverseMembership> members = membershipMapper.selectList(
                new LambdaQueryWrapper<QuantUniverseMembership>()
                        .eq(QuantUniverseMembership::getUniverseId, universe.getId())
                        .orderByAsc(QuantUniverseMembership::getValidFrom)
                        .orderByAsc(QuantUniverseMembership::getCode)
        );
        return Map.of(
                "id", universe.getId(),
                "universeCode", universe.getUniverseCode(),
                "name", universe.getName(),
                "modelFamily", universe.getModelFamily(),
                "market", universe.getMarket() == null ? "" : universe.getMarket(),
                "selectionRule", readJson(universe.getSelectionRuleJson()),
                "datasetVersion", universeVersion(universe),
                "memberCount", members.size()
        );
    }

    private QuantResearchUniverse resolveUniverse(Long universeId, String modelFamily) {
        QuantResearchUniverse universe = universeId == null
                ? universeMapper.selectOne(new LambdaQueryWrapper<QuantResearchUniverse>()
                        .eq(QuantResearchUniverse::getModelFamily, modelFamily)
                        .eq(QuantResearchUniverse::getActive, true)
                        .orderByAsc(QuantResearchUniverse::getId)
                        .last("LIMIT 1"))
                : universeMapper.selectById(universeId);
        if (universe != null && (!Boolean.TRUE.equals(universe.getActive())
                || !modelFamily.equals(universe.getModelFamily()))) {
            throw new IllegalArgumentException("研究资产池与模型家族不匹配");
        }
        return universe;
    }

    private String universeVersion(QuantResearchUniverse universe) {
        if (universe == null) return null;
        if (universe.getDatasetVersion() != null && !universe.getDatasetVersion().isBlank()) {
            return universe.getDatasetVersion();
        }
        List<Map<String, Object>> members = membershipMapper.selectList(
                new LambdaQueryWrapper<QuantUniverseMembership>()
                        .eq(QuantUniverseMembership::getUniverseId, universe.getId())
                        .orderByAsc(QuantUniverseMembership::getValidFrom)
                        .orderByAsc(QuantUniverseMembership::getCode)
        ).stream().map(item -> Map.<String, Object>of(
                "productId", item.getProductId() == null ? "" : item.getProductId(),
                "productType", item.getProductType(),
                "market", item.getMarket() == null ? "" : item.getMarket(),
                "code", item.getCode(),
                "validFrom", item.getValidFrom().toString(),
                "validTo", item.getValidTo() == null ? "" : item.getValidTo().toString(),
                "sourceSnapshot", item.getSourceSnapshot()
        )).toList();
        return QuantResearchCatalog.canonicalHash(members);
    }

    private Map<String, Object> validationReportView(QuantValidationReport report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelVersion", report.getModelVersion());
        result.put("lifecycle", report.getLifecycle());
        result.put("passed", report.getPassed());
        result.put("failureCodes", readJsonValue(report.getFailureCodesJson(), List.of()));
        result.put("checks", readJsonValue(report.getChecksJson(), List.of()));
        result.put("metrics", readJson(report.getMetricsJson()));
        result.put("datasetVersion", report.getDatasetVersion());
        result.put("featureSetVersion", report.getFeatureSetVersion());
        result.put("quantConfigVersion", report.getQuantConfigVersion());
        return result;
    }

    private Map<String, Object> benchmarkView(BenchmarkProfile profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.getId());
        result.put("productType", profile.getProductType());
        result.put("productCode", profile.getProductCode());
        result.put("modelFamily", profile.getModelFamily());
        result.put("benchmarkCode", profile.getBenchmarkCode());
        result.put("displayName", profile.getDisplayName());
        result.put("composition", readJson(profile.getCompositionJson()));
        result.put("currency", profile.getCurrency());
        result.put("fxRule", profile.getFxRule());
        result.put("sourceUri", profile.getSourceUri());
        result.put("sourceVersion", profile.getSourceVersion());
        result.put("effectiveFrom", profile.getEffectiveFrom());
        result.put("effectiveTo", profile.getEffectiveTo());
        return result;
    }

    private void validateParameters(Map<String, Object> parameters) {
        Map<String, Map<String, Object>> allowed = new LinkedHashMap<>();
        for (Object raw : (List<?>) QuantResearchCatalog.parameterSchema().get("fields")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> field = (Map<String, Object>) raw;
            allowed.put(String.valueOf(field.get("key")), field);
        }
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            Map<String, Object> field = allowed.get(entry.getKey());
            if (field == null) {
                throw new IllegalArgumentException("不支持的模型参数: " + entry.getKey());
            }
            if (!(entry.getValue() instanceof Number number)) {
                throw new IllegalArgumentException("模型参数必须是数字: " + entry.getKey());
            }
            double value = number.doubleValue();
            double minimum = ((Number) field.get("minimum")).doubleValue();
            double maximum = ((Number) field.get("maximum")).doubleValue();
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("模型参数超出允许范围: " + entry.getKey());
            }
        }
    }

    private static String normalizeFamily(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!MODEL_FAMILIES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的模型家族");
        }
        return normalized;
    }

    private static String normalizeHorizon(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SHORT", "MEDIUM", "LONG").contains(normalized)) {
            throw new IllegalArgumentException("不支持的预测周期");
        }
        return normalized;
    }

    private static String normalizeAlgorithm(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ALGORITHMS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的量化算法");
        }
        return normalized;
    }

    private static int horizonDays(String horizon) {
        return switch (horizon) {
            case "SHORT" -> 20;
            case "MEDIUM" -> 60;
            case "LONG" -> 250;
            default -> throw new IllegalArgumentException("不支持的预测周期");
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("量化实验配置无法序列化", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            return Map.of("invalid", true);
        }
    }

    private Object readJsonValue(String value, Object fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static String errorSummary(RuntimeException exception) {
        String message = exception.getMessage() == null ? "unknown error" : exception.getMessage();
        String summary = exception.getClass().getSimpleName() + ": " + message;
        return summary.substring(0, Math.min(500, summary.length()));
    }
}
