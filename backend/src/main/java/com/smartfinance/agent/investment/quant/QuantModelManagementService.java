package com.smartfinance.agent.investment.quant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QuantModelManagementService {
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final QuantStrategyVersionMapper strategyMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantJobMapper jobMapper;
    private final ObjectMapper objectMapper;

    public QuantModelManagementService(InvestmentAssetMapper assetMapper,
                                       InvestmentProductMapper productMapper,
                                       QuantStrategyVersionMapper strategyMapper,
                                       QuantModelVersionMapper modelMapper,
                                       QuantJobMapper jobMapper,
                                       ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.strategyMapper = strategyMapper;
        this.modelMapper = modelMapper;
        this.jobMapper = jobMapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> management(Long userId,
                                          Long assetId,
                                          String horizonCode) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct product = requireProduct(asset.getProductId());
        String horizon = normalizeHorizon(horizonCode);
        QuantStrategyVersion champion = latestRole(userId, assetId, horizon, "CHAMPION");
        QuantStrategyVersion challenger = latestRole(userId, assetId, horizon, "CHALLENGER");
        QuantJob latestJob = jobMapper.selectOne(new LambdaQueryWrapper<QuantJob>()
                .eq(QuantJob::getUserId, userId)
                .eq(QuantJob::getAssetId, assetId)
                .eq(QuantJob::getHorizonCode, horizon)
                .orderByDesc(QuantJob::getCreatedAt)
                .last("LIMIT 1"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetId", assetId);
        result.put("productId", product.getId());
        result.put("assetName", product.getName());
        result.put("productType", product.getProductType());
        result.put("horizonCode", horizon);
        result.put("automaticTraining", true);
        result.put("training", trainingView(latestJob));
        result.put("technicalSignal", technicalSignalView(latestJob));
        result.put("champion", strategyView(champion));
        result.put("challenger", strategyView(challenger));
        result.put("availableActions", List.of(
                "RETRAIN",
                "TUNE",
                "VIEW_HISTORY",
                "ROLLBACK"
        ));
        return result;
    }

    public List<Map<String, Object>> models(Long userId, Long assetId) {
        requireAsset(userId, assetId);
        return strategyMapper.selectList(new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, userId)
                        .eq(QuantStrategyVersion::getAssetId, assetId)
                        .orderByDesc(QuantStrategyVersion::getCreatedAt))
                .stream()
                .map(this::strategyView)
                .toList();
    }

    private QuantStrategyVersion latestRole(Long userId,
                                            Long assetId,
                                            String horizonCode,
                                            String deploymentRole) {
        return strategyMapper.selectOne(new LambdaQueryWrapper<QuantStrategyVersion>()
                .eq(QuantStrategyVersion::getUserId, userId)
                .eq(QuantStrategyVersion::getAssetId, assetId)
                .eq(QuantStrategyVersion::getHorizonCode, horizonCode)
                .eq(QuantStrategyVersion::getDeploymentRole, deploymentRole)
                .orderByDesc(QuantStrategyVersion::getActivatedAt)
                .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                .last("LIMIT 1"));
    }

    private static String normalizeHorizon(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择预测周期");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> strategyView(QuantStrategyVersion strategy) {
        if (strategy == null) {
            return null;
        }
        QuantModelVersion model = modelMapper.selectOne(
                new LambdaQueryWrapper<QuantModelVersion>()
                        .eq(QuantModelVersion::getModelVersion, strategy.getModelVersion())
                        .last("LIMIT 1")
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyVersion", strategy.getStrategyVersion());
        result.put("modelVersion", strategy.getModelVersion());
        result.put("modelLifecycle", model == null ? "DRAFT" : model.getStatus());
        result.put("deploymentRole", strategy.getDeploymentRole());
        result.put("deploymentStatus", model == null || model.getDeploymentStatus() == null
                ? strategy.getStatus()
                : model.getDeploymentStatus());
        result.put("economicRole", model == null ? null : model.getEconomicRole());
        result.put("baselineComparison", model == null
                ? Map.of()
                : readResult(model.getBaselineComparisonJson()));
        result.put("modelFamily", strategy.getModelFamily());
        result.put("horizonCode", strategy.getHorizonCode());
        result.put("activatedAt", strategy.getActivatedAt());
        result.put("retiredAt", strategy.getRetiredAt());
        return result;
    }

    private Map<String, Object> trainingView(QuantJob job) {
        if (job == null) {
            return Map.of(
                    "status", "NOT_STARTED",
                    "label", "尚未开始自动训练"
            );
        }
        Map<String, Object> technicalSignal = technicalSignalView(job);
        Map<String, Object> storedResult = readResult(job.getResultJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", job.getExternalJobId());
        result.put("status", job.getStatus());
        result.put("executionStatus", firstNonBlank(
                job.getExecutionStatus(),
                legacyExecutionStatus(job.getStatus())
        ));
        result.put("trainingOutcome", job.getTrainingOutcome());
        result.put("deploymentStatus", job.getDeploymentStatus());
        result.put("economicRole", job.getEconomicRole());
        result.put("label", trainingLabel(job, technicalSignal != null));
        result.put("errorCode", job.getErrorCode());
        result.put("userMessage", job.getUserMessage());
        result.put("horizonCode", job.getHorizonCode());
        result.put("createdAt", job.getCreatedAt());
        result.put("startedAt", job.getStartedAt());
        result.put("finishedAt", job.getFinishedAt());
        result.put("estimatedDurationSeconds", job.getEstimatedDurationSeconds());
        LocalDateTime durationStart = job.getStartedAt() == null
                ? job.getCreatedAt()
                : job.getStartedAt();
        if (durationStart != null && job.getFinishedAt() != null) {
            result.put(
                    "actualDurationSeconds",
                    Math.max(0L, Duration.between(
                            durationStart,
                            job.getFinishedAt()
                    ).getSeconds())
            );
        }
        result.put("baselineComparison", storedResult.getOrDefault(
                "baselineComparison",
                readResult(job.getBaselineComparisonJson())
        ));
        result.put("optimizationSummary", storedResult.getOrDefault(
                "optimizationSummary",
                storedResult.getOrDefault("searchSummary", Map.of())
        ));
        result.put("diagnostics", storedResult.getOrDefault(
                "diagnostics",
                readResult(job.getDiagnosticsJson())
        ));
        if (technicalSignal != null
                && "VALIDATION_FAILED".equals(job.getTrainingOutcome())) {
            result.put("continuation", "CONTINUE_ON_NEW_DATA_OR_VERSION");
        }
        return result;
    }

    private static String trainingLabel(QuantJob job, boolean technicalSignalAvailable) {
        String execution = firstNonBlank(
                job.getExecutionStatus(),
                legacyExecutionStatus(job.getStatus())
        );
        if ("FAILED".equals(execution)) {
            return "自动训练执行失败";
        }
        if ("DATA_BLOCKED".equals(job.getTrainingOutcome())) {
            return "数据未准备完整，暂不启动正式训练";
        }
        if ("VALIDATION_FAILED".equals(job.getTrainingOutcome())) {
            return technicalSignalAvailable
                    ? "执行完成、验证未通过；已保留风险参考"
                    : "执行完成、验证未通过";
        }
        if ("VALIDATED".equals(job.getTrainingOutcome())) {
            return "执行完成，模型已通过经济验证";
        }
        if ("INSUFFICIENT_DATA".equals(job.getErrorCode())) {
            return "历史数据不足，系统将在数据补充后继续训练";
        }
        if ("BENCHMARK_UNAVAILABLE".equals(job.getErrorCode())) {
            return "官方基准正在准备，完成后将自动继续训练";
        }
        return switch (execution) {
            case "QUEUED" -> "等待自动训练";
            case "RUNNING" -> "正在自动训练和验证";
            case "COMPLETED" -> job.getModelVersion() == null
                    ? "执行完成，等待下一批数据继续优化"
                    : "自动训练已完成";
            case "CANCELLED" -> "自动训练已取消";
            default -> "尚未开始自动训练";
        };
    }

    private Map<String, Object> technicalSignalView(QuantJob job) {
        if (job == null || (!"SUCCEEDED".equals(job.getStatus())
                && !"COMPLETED".equals(job.getExecutionStatus()))) {
            return null;
        }
        Map<String, Object> result = readResult(job.getResultJson());
        if (!"DRAFT".equals(result.get("modelStatus"))
                && !"RISK_REFERENCE".equals(job.getEconomicRole())) {
            return null;
        }
        Object rawSummary = result.get("searchSummary");
        if (!(rawSummary instanceof Map<?, ?> summary)
                || !Boolean.TRUE.equals(summary.get("technicalSignalAvailable"))) {
            return null;
        }
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("status", "READY");
        signal.put("tradable", false);
        signal.put("economicRole", "RISK_REFERENCE");
        signal.put("signal", technicalDirection(result));
        signal.put("profitProbability", result.get("profitProbability"));
        signal.put("lossProbability", result.get("lossProbability"));
        signal.put("expectedNetReturn", result.get("expectedNetReturn"));
        signal.put("predictionInterval", result.get("predictionInterval"));
        signal.put("marketRegime", result.get("marketRegime"));
        Object rawRiskReference = result.get("riskReference");
        if (rawRiskReference instanceof Map<?, ?> riskReference) {
            Object marketRegime = riskReference.get("marketRegime");
            signal.put("marketRegime", marketRegime == null
                    ? result.get("marketRegime")
                    : marketRegime);
            signal.put("annualizedVolatility", riskReference.get(
                    "annualizedVolatility"
            ));
            signal.put("positionLimit", riskReference.get("positionLimit"));
            signal.put("drawdownWarning", riskReference.get("drawdownWarning"));
        }
        signal.put("baselineComparison", result.getOrDefault(
                "baselineComparison",
                Map.of()
        ));
        signal.put("optimizationSummary", result.getOrDefault(
                "optimizationSummary",
                result.getOrDefault("searchSummary", Map.of())
        ));
        signal.put("topFactors", result.getOrDefault("topFactors", List.of()));
        signal.put("userMessage", technicalSignalMessage(result));
        return signal;
    }

    private static String technicalDirection(Map<String, Object> result) {
        double profit = number(result.get("profitProbability"));
        double loss = number(result.get("lossProbability"));
        double expected = number(result.get("expectedNetReturn"));
        if (profit >= 0.55 && expected > 0) {
            return "POSITIVE";
        }
        if (loss >= 0.55 || expected < 0) {
            return "NEGATIVE";
        }
        return "NEUTRAL";
    }

    private static String technicalSignalMessage(Map<String, Object> result) {
        return switch (technicalDirection(result)) {
            case "POSITIVE" -> "当前技术信号偏强；交易模型仍在验证，暂不生成买入金额";
            case "NEGATIVE" -> "当前技术信号偏弱；交易模型仍在验证，暂不增加仓位";
            default -> "当前技术信号不明确；交易模型仍在验证，继续观察";
        };
    }

    private Map<String, Object> readResult(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String legacyExecutionStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "SUCCEEDED" -> "COMPLETED";
            default -> status;
        };
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
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
}
