package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.springframework.stereotype.Service;

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

    public QuantModelManagementService(InvestmentAssetMapper assetMapper,
                                       InvestmentProductMapper productMapper,
                                       QuantStrategyVersionMapper strategyMapper,
                                       QuantModelVersionMapper modelMapper,
                                       QuantJobMapper jobMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.strategyMapper = strategyMapper;
        this.modelMapper = modelMapper;
        this.jobMapper = jobMapper;
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
        result.put("deploymentStatus", strategy.getStatus());
        result.put("modelFamily", strategy.getModelFamily());
        result.put("horizonCode", strategy.getHorizonCode());
        result.put("activatedAt", strategy.getActivatedAt());
        result.put("retiredAt", strategy.getRetiredAt());
        return result;
    }

    private static Map<String, Object> trainingView(QuantJob job) {
        if (job == null) {
            return Map.of(
                    "status", "NOT_STARTED",
                    "label", "尚未开始自动训练"
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", job.getExternalJobId());
        result.put("status", job.getStatus());
        result.put("label", trainingLabel(job));
        result.put("errorCode", job.getErrorCode());
        result.put("userMessage", job.getUserMessage());
        result.put("horizonCode", job.getHorizonCode());
        result.put("createdAt", job.getCreatedAt());
        result.put("finishedAt", job.getFinishedAt());
        return result;
    }

    private static String trainingLabel(QuantJob job) {
        if ("FAILED".equals(job.getStatus())) {
            return "自动训练执行失败";
        }
        if (job.getErrorCode() != null) {
            return "自动训练已结束，当前没有合格模型";
        }
        return switch (job.getStatus()) {
            case "QUEUED" -> "等待自动训练";
            case "RUNNING" -> "正在自动训练和验证";
            case "SUCCEEDED" -> job.getModelVersion() == null
                    ? "自动训练已结束，当前没有合格模型"
                    : "自动训练已完成";
            case "CANCELLED" -> "自动训练已取消";
            default -> "尚未开始自动训练";
        };
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
