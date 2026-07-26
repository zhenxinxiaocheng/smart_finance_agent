package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class QuantModelRegistryService {
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final QuantExperimentMapper experimentMapper;
    private final QuantFeatureSetMapper featureSetMapper;
    private final QuantBacktestRunMapper backtestRunMapper;
    private final QuantPredictionMapper predictionMapper;
    private final QuantValidationReportMapper validationReportMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantStrategyVersionMapper strategyMapper;
    private final PaperTradingService paperTradingService;
    private final ObjectMapper objectMapper;

    public QuantModelRegistryService(InvestmentAssetMapper assetMapper,
                                     InvestmentProductMapper productMapper,
                                     QuantExperimentMapper experimentMapper,
                                     QuantFeatureSetMapper featureSetMapper,
                                     QuantBacktestRunMapper backtestRunMapper,
                                     QuantPredictionMapper predictionMapper,
                                     QuantValidationReportMapper validationReportMapper,
                                     QuantModelVersionMapper modelMapper,
                                     QuantStrategyVersionMapper strategyMapper,
                                     PaperTradingService paperTradingService,
                                     ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.experimentMapper = experimentMapper;
        this.featureSetMapper = featureSetMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.predictionMapper = predictionMapper;
        this.validationReportMapper = validationReportMapper;
        this.modelMapper = modelMapper;
        this.strategyMapper = strategyMapper;
        this.paperTradingService = paperTradingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persistArtifacts(QuantJob job, Map<String, Object> result) {
        String featureSetVersion = text(result.get("featureSetVersion"));
        String modelVersion = text(result.get("modelVersion"));
        String strategyVersion = text(result.get("strategyVersion"));
        boolean strictlyValidated = isStrictlyValidated(result);
        if (featureSetVersion == null) {
            return;
        }
        persistFeatureSet(job, result, featureSetVersion);
        if (modelVersion != null) {
            persistModelAndStrategy(job, result, modelVersion, strategyVersion, strictlyValidated);
            persistValidationReport(job, result, modelVersion);
            persistBacktestRun(job, result, modelVersion, strategyVersion);
        }
        if (!strictlyValidated) {
            return;
        }
        QuantPrediction existing = predictionMapper.selectOne(new LambdaQueryWrapper<QuantPrediction>()
                .eq(QuantPrediction::getUserId, job.getUserId())
                .eq(QuantPrediction::getAssetId, job.getAssetId())
                .eq(QuantPrediction::getDatasetVersion, job.getDatasetVersion())
                .eq(QuantPrediction::getHorizonCode, job.getHorizonCode())
                .eq(modelVersion != null, QuantPrediction::getModelVersion, modelVersion)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        QuantPrediction prediction = new QuantPrediction();
        prediction.setUserId(job.getUserId());
        prediction.setAssetId(job.getAssetId());
        prediction.setDatasetVersion(job.getDatasetVersion());
        prediction.setFeatureSetVersion(featureSetVersion);
        prediction.setModelVersion(modelVersion);
        prediction.setStrategyVersion(strategyVersion);
        prediction.setModelFamily(text(result.get("modelFamily")));
        prediction.setHorizonProfileVersion(job.getHorizonProfileVersion());
        prediction.setHorizonCode(job.getHorizonCode());
        prediction.setHorizonDays(job.getHorizonDays());
        prediction.setAsOfDate(LocalDate.parse(String.valueOf(result.get("asOfDate"))));
        prediction.setProfitProbability(decimal(result.get("profitProbability")));
        prediction.setLossProbability(decimal(result.get("lossProbability")));
        prediction.setExpectedNetReturn(decimal(result.get("expectedNetReturn")));
        prediction.setProbabilityPositiveExcess(decimal(result.get("probabilityPositiveExcess")));
        prediction.setExpectedExcessReturn(decimal(result.get("expectedExcessReturn")));
        if (result.get("predictionInterval") instanceof List<?> interval && interval.size() == 2) {
            prediction.setIntervalLower(decimal(interval.get(0)));
            prediction.setIntervalUpper(decimal(interval.get(1)));
        }
        prediction.setConfidence(String.valueOf(result.getOrDefault("confidence", "LOW")));
        prediction.setAction(String.valueOf(result.getOrDefault("action", "NO_TRADE")));
        prediction.setTargetWeight(
                Objects.requireNonNullElse(decimal(result.get("targetWeight")), BigDecimal.ZERO));
        prediction.setMarketRegime(text(result.get("marketRegime")));
        prediction.setBenchmarkCode(text(result.get("benchmarkCode")));
        prediction.setRoundTripCostBps(decimal(result.get("roundTripCostBps")));
        prediction.setFeatureVectorJson(writeJson(result.getOrDefault("featureVector", Map.of())));
        prediction.setTopFactorsJson(writeJson(result.getOrDefault("topFactors", List.of())));
        prediction.setRiskFlagsJson(writeJson(result.getOrDefault("riskFlags", List.of())));
        prediction.setBacktestSummaryJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
        predictionMapper.insert(prediction);
        if (modelVersion != null && job.getExperimentFingerprint() == null) {
            InvestmentProduct product = requireProduct(
                    requireAsset(job.getUserId(), job.getAssetId()).getProductId());
            paperTradingService.queueValidatedPrediction(
                    job.getUserId(),
                    product,
                    prediction,
                    String.valueOf(result.get("modelStatus"))
            );
        }
    }

    @Transactional
    public void activatePaperModel(Long userId, String modelVersion) {
        activatePaperModel(userId, null, modelVersion);
    }

    @Transactional
    public void activatePaperModel(Long userId, Long assetId, String modelVersion) {
        QuantModelVersion model = modelMapper.selectOne(new LambdaQueryWrapper<QuantModelVersion>()
                .eq(QuantModelVersion::getModelVersion, modelVersion)
                .last("LIMIT 1"));
        if (model == null || !List.of("VALIDATED", "PAPER_VERIFIED").contains(model.getStatus())) {
            throw new IllegalStateException("只有通过严格验证的模型才能晋级模拟盘");
        }
        QuantValidationReport report = validationReportMapper.selectOne(
                new LambdaQueryWrapper<QuantValidationReport>()
                        .eq(QuantValidationReport::getModelVersion, modelVersion)
                        .eq(QuantValidationReport::getPassed, true)
                        .orderByDesc(QuantValidationReport::getCreatedAt)
                        .last("LIMIT 1"));
        if (report == null) {
            throw new IllegalStateException("缺少通过状态的严格验证报告，不能晋级模拟盘");
        }
        QuantPrediction prediction = predictionMapper.selectOne(
                new LambdaQueryWrapper<QuantPrediction>()
                        .eq(QuantPrediction::getUserId, userId)
                        .eq(assetId != null, QuantPrediction::getAssetId, assetId)
                        .eq(QuantPrediction::getModelVersion, modelVersion)
                        .orderByDesc(QuantPrediction::getAsOfDate)
                        .last("LIMIT 1"));
        if (prediction == null) {
            throw new IllegalStateException("模型没有可用于模拟盘的预测结果");
        }
        QuantStrategyVersion strategy = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getModelVersion, modelVersion)
                        .last("LIMIT 1"));
        if (strategy == null) {
            throw new IllegalStateException("模型没有对应的策略版本");
        }
        strategy.setUserId(userId);
        strategy.setAssetId(prediction.getAssetId());
        strategy.setModelFamily(prediction.getModelFamily());
        strategy.setHorizonCode(prediction.getHorizonCode());
        strategy.setDeploymentRole("CHALLENGER");
        if (!List.of("PAPER", "CHAMPION").contains(strategy.getStatus())) {
            strategy.setStatus("PAPER");
            strategy.setActivatedAt(LocalDateTime.now());
        }
        strategyMapper.updateById(strategy);
        InvestmentProduct product = requireProduct(
                requireAsset(userId, prediction.getAssetId()).getProductId());
        paperTradingService.queueValidatedPrediction(
                userId,
                product,
                prediction,
                model.getStatus()
        );
    }

    public Map<String, Object> strategyStatus(Long userId) {
        List<QuantStrategyVersion> strategies = strategyMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, userId)
                        .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT 20"));
        return Map.of(
                "status", strategies.isEmpty() ? "NOT_READY" : "READY",
                "strategies", strategies.stream().map(item -> Map.of(
                        "strategyVersion", item.getStrategyVersion(),
                        "modelVersion", item.getModelVersion(),
                        "productType", item.getProductType(),
                        "status", item.getStatus(),
                        "deploymentRole", item.getDeploymentRole()
                )).toList()
        );
    }

    private void persistFeatureSet(QuantJob job,
                                   Map<String, Object> result,
                                   String featureSetVersion) {
        if (featureSetMapper.selectCount(new LambdaQueryWrapper<QuantFeatureSet>()
                .eq(QuantFeatureSet::getFeatureSetVersion, featureSetVersion)) != 0) {
            return;
        }
        InvestmentProduct product = requireProduct(
                requireAsset(job.getUserId(), job.getAssetId()).getProductId());
        QuantFeatureSet featureSet = new QuantFeatureSet();
        featureSet.setFeatureSetVersion(featureSetVersion);
        featureSet.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
        featureSet.setProductType(product.getProductType());
        featureSet.setSchemaJson(writeJson(result.getOrDefault("featureSchema", List.of())));
        featureSet.setArtifactUri(text(result.get("featureArtifactUri")));
        featureSet.setArtifactHash(text(result.get("featureArtifactHash")));
        featureSetMapper.insert(featureSet);
    }

    private void persistBacktestRun(QuantJob job,
                                    Map<String, Object> result,
                                    String modelVersion,
                                    String strategyVersion) {
        if (backtestRunMapper.selectCount(new LambdaQueryWrapper<QuantBacktestRun>()
                .eq(QuantBacktestRun::getModelVersion, modelVersion)
                .eq(QuantBacktestRun::getDatasetVersion, job.getDatasetVersion())
                .eq(QuantBacktestRun::getHorizonDays, job.getHorizonDays())) > 0) {
            return;
        }
        QuantBacktestRun run = new QuantBacktestRun();
        run.setModelVersion(modelVersion);
        run.setStrategyVersion(strategyVersion);
        run.setDatasetVersion(job.getDatasetVersion());
        run.setHorizonDays(job.getHorizonDays());
        run.setStatus("SUCCEEDED");
        run.setMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
        run.setStartedAt(Objects.requireNonNullElse(job.getStartedAt(), job.getCreatedAt()));
        run.setFinishedAt(Objects.requireNonNullElse(job.getFinishedAt(), LocalDateTime.now()));
        backtestRunMapper.insert(run);
    }

    private void persistModelAndStrategy(QuantJob job,
                                         Map<String, Object> result,
                                         String modelVersion,
                                         String strategyVersion,
                                         boolean strictlyValidated) {
        boolean autoPaper = strictlyValidated && job.getExperimentFingerprint() == null;
        InvestmentProduct product = requireProduct(
                requireAsset(job.getUserId(), job.getAssetId()).getProductId());
        if (modelMapper.selectCount(new LambdaQueryWrapper<QuantModelVersion>()
                .eq(QuantModelVersion::getModelVersion, modelVersion)) == 0) {
            QuantModelVersion model = new QuantModelVersion();
            model.setModelVersion(modelVersion);
            model.setFeatureSetVersion(String.valueOf(result.get("featureSetVersion")));
            model.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
            model.setProductType(product.getProductType());
            model.setHorizonDays(job.getHorizonDays());
            model.setStatus(strictlyValidated
                    ? String.valueOf(result.getOrDefault("modelStatus", "VALIDATED"))
                    : "DRAFT");
            model.setArtifactUri("analysis-service://quant-models/" + modelVersion);
            model.setArtifactHash(String.valueOf(result.get("modelFileHash")));
            model.setMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
            model.setTrainedAt(LocalDateTime.now());
            modelMapper.insert(model);
        }
        if (strategyVersion != null && strategyMapper.selectCount(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getStrategyVersion, strategyVersion)
        ) == 0) {
            QuantStrategyVersion strategy = new QuantStrategyVersion();
            strategy.setStrategyVersion(strategyVersion);
            strategy.setModelVersion(modelVersion);
            strategy.setUserId(job.getUserId());
            strategy.setAssetId(job.getAssetId());
            strategy.setProductType(product.getProductType());
            strategy.setModelFamily(text(result.get("modelFamily")));
            strategy.setHorizonCode(job.getHorizonCode());
            strategy.setDeploymentRole(autoPaper ? "CHALLENGER" : "ARCHIVED");
            strategy.setStatus(autoPaper ? "PAPER" : "DRAFT");
            strategy.setValidationMetricsJson(
                    writeJson(result.getOrDefault("backtestSummary", Map.of())));
            if (autoPaper) {
                strategy.setActivatedAt(LocalDateTime.now());
            }
            strategyMapper.insert(strategy);
        }
    }

    private void persistValidationReport(QuantJob job,
                                         Map<String, Object> result,
                                         String modelVersion) {
        if (validationReportMapper.selectCount(
                new LambdaQueryWrapper<QuantValidationReport>()
                        .eq(QuantValidationReport::getModelVersion, modelVersion)
        ) > 0) {
            return;
        }
        Object rawReport = result.get("validationReport");
        if (!(rawReport instanceof Map<?, ?> report)) {
            return;
        }
        QuantValidationReport entity = new QuantValidationReport();
        entity.setExperimentId(findExperiment(job).map(QuantExperiment::getId).orElse(null));
        entity.setModelVersion(modelVersion);
        Object lifecycle = report.get("lifecycle");
        entity.setLifecycle(lifecycle == null ? "DRAFT" : String.valueOf(lifecycle));
        entity.setPassed(Boolean.TRUE.equals(report.get("passed")));
        Object failureCodes = report.get("failureCodes");
        Object checks = report.get("checks");
        entity.setFailureCodesJson(writeJson(failureCodes == null ? List.of() : failureCodes));
        entity.setChecksJson(writeJson(checks == null ? List.of() : checks));
        entity.setMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
        entity.setDatasetVersion(job.getDatasetVersion());
        entity.setFeatureSetVersion(job.getFeatureSetVersion());
        entity.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
        validationReportMapper.insert(entity);
    }

    private java.util.Optional<QuantExperiment> findExperiment(QuantJob job) {
        if (job.getExperimentFingerprint() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(experimentMapper.selectOne(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, job.getUserId())
                        .eq(
                                QuantExperiment::getExperimentFingerprint,
                                job.getExperimentFingerprint()
                        )
                        .last("LIMIT 1")
        ));
    }

    private InvestmentAsset requireAsset(Long userId, Long assetId) {
        InvestmentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getId, assetId)
                .eq(InvestmentAsset::getUserId, userId)
                .last("LIMIT 1"));
        if (asset == null) {
            throw new IllegalArgumentException("投资资产不存在");
        }
        return asset;
    }

    private InvestmentProduct requireProduct(Long productId) {
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new IllegalArgumentException("投资产品不存在");
        }
        return product;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("量化结果保存失败", exception);
        }
    }

    private static boolean isStrictlyValidated(Map<String, Object> result) {
        String lifecycle = text(result.get("modelStatus"));
        if (!List.of("VALIDATED", "PAPER_VERIFIED").contains(lifecycle)) {
            return false;
        }
        Object reportValue = result.get("validationReport");
        if (!(reportValue instanceof Map<?, ?> report)) {
            return false;
        }
        return Boolean.TRUE.equals(report.get("passed"));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
