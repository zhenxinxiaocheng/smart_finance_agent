package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualitySnapshotMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QuantPredictionQueryService {
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualitySnapshotMapper qualityMapper;
    private final InvestmentHorizonService horizonService;
    private final QuantPredictionMapper predictionMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantStrategyVersionMapper strategyMapper;
    private final WealthService wealthService;
    private final ObjectMapper objectMapper;

    public QuantPredictionQueryService(InvestmentAssetMapper assetMapper,
                                       InvestmentProductMapper productMapper,
                                       ProductDailyQuoteMapper quoteMapper,
                                       InvestmentDataQualitySnapshotMapper qualityMapper,
                                       InvestmentHorizonService horizonService,
                                       QuantPredictionMapper predictionMapper,
                                       QuantModelVersionMapper modelMapper,
                                       QuantStrategyVersionMapper strategyMapper,
                                       WealthService wealthService,
                                       ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.qualityMapper = qualityMapper;
        this.horizonService = horizonService;
        this.predictionMapper = predictionMapper;
        this.modelMapper = modelMapper;
        this.strategyMapper = strategyMapper;
        this.wealthService = wealthService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> latestAnalysis(Long userId, Long assetId, String horizonCode) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct product = requireProduct(asset.getProductId());
        String normalizedHorizon = normalizeHorizon(horizonCode);
        ResolvedHorizonProfile profile = horizonService.resolve(userId, assetId);
        HorizonSetting horizon = profile.settings().stream()
                .filter(item -> item.code().equals(normalizedHorizon))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到指定分析周期"));
        InvestmentDataQualitySnapshot quality = latestQuality(product);
        if (quality == null || quality.getDatasetVersion() == null) {
            return unavailable(
                    normalizedHorizon,
                    "INSUFFICIENT_DATA",
                    "研究数据不足，当前不生成交易建议。"
            );
        }
        if ("BLOCKED".equalsIgnoreCase(quality.getQualityStatus())
                || !"ALLOW".equalsIgnoreCase(quality.getDecision())) {
            return unavailable(
                    normalizedHorizon,
                    "DATA_STALE",
                    "最新研究数据尚未通过质量校验，当前不使用旧模型冒充有效结果。"
            );
        }
        LambdaQueryWrapper<QuantPrediction> query = new LambdaQueryWrapper<QuantPrediction>()
                .eq(QuantPrediction::getUserId, userId)
                .eq(QuantPrediction::getAssetId, assetId)
                .eq(QuantPrediction::getHorizonCode, normalizedHorizon)
                .eq(QuantPrediction::getHorizonDays, horizon.targetHoldingDays())
                .eq(QuantPrediction::getHorizonProfileVersion, profile.version())
                .eq(QuantPrediction::getDatasetVersion, quality.getDatasetVersion())
                .orderByDesc(QuantPrediction::getAsOfDate)
                .orderByDesc(QuantPrediction::getCreatedAt);
        Map<String, Object> result = null;
        for (QuantPrediction prediction : predictionMapper.selectList(query)) {
            Map<String, Object> candidate = predictionView(prediction);
            if ("READY".equals(candidate.get("status"))) {
                result = candidate;
                break;
            }
        }
        if (result == null) {
            return unavailable(normalizedHorizon);
        }
        List<ProductDailyQuote> currentQuotes = loadQuotes(product);
        BigDecimal latestPrice = currentQuotes.isEmpty()
                ? null
                : currentQuotes.get(currentQuotes.size() - 1).getClosePrice();
        result.put("currentQuantity", asset.getQuantity());
        result.put("currentWeight", PortfolioWeightCalculator.calculate(
                asset.getQuantity(),
                latestPrice,
                wealthService.overview(userId).getTotalAssets()
        ));
        return result;
    }

    private Map<String, Object> predictionView(QuantPrediction prediction) {
        QuantModelVersion model = prediction.getModelVersion() == null
                ? null
                : modelMapper.selectOne(new LambdaQueryWrapper<QuantModelVersion>()
                        .eq(QuantModelVersion::getModelVersion, prediction.getModelVersion())
                        .last("LIMIT 1"));
        String lifecycle = model == null ? "DRAFT" : model.getStatus();
        boolean tradable = List.of("VALIDATED", "PAPER_VERIFIED").contains(lifecycle);
        QuantStrategyVersion deployment = prediction.getStrategyVersion() == null
                ? null
                : strategyMapper.selectOne(new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getStrategyVersion, prediction.getStrategyVersion())
                        .in(QuantStrategyVersion::getStatus, "PAPER", "CHAMPION", "CHALLENGER")
                        .last("LIMIT 1"));
        if (!tradable || deployment == null) {
            return unavailable(
                    prediction.getHorizonCode(),
                    "MODEL_UNAVAILABLE",
                    "暂无有效量化模型"
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "READY");
        result.put("modelLifecycle", lifecycle);
        result.put("deploymentStatus", deployment.getStatus());
        result.put("datasetVersion", prediction.getDatasetVersion());
        result.put("featureSetVersion", prediction.getFeatureSetVersion());
        result.put("modelVersion", prediction.getModelVersion());
        result.put("strategyVersion", prediction.getStrategyVersion());
        result.put("horizonProfileVersion", prediction.getHorizonProfileVersion());
        result.put("horizonCode", prediction.getHorizonCode());
        result.put("horizonDays", prediction.getHorizonDays());
        result.put("asOfDate", prediction.getAsOfDate());
        result.put("probabilityPositiveExcess", prediction.getProbabilityPositiveExcess());
        result.put("expectedExcessReturn", prediction.getExpectedExcessReturn());
        List<BigDecimal> predictionInterval = new ArrayList<>();
        predictionInterval.add(prediction.getIntervalLower());
        predictionInterval.add(prediction.getIntervalUpper());
        result.put("predictionInterval", predictionInterval);
        result.put("confidence", prediction.getConfidence());
        result.put("action", prediction.getAction());
        result.put("targetWeight", prediction.getTargetWeight());
        result.put("recommendedTargetWeight", prediction.getTargetWeight());
        result.put("marketRegime", prediction.getMarketRegime());
        result.put("benchmarkCode", prediction.getBenchmarkCode());
        result.put("roundTripCostBps", prediction.getRoundTripCostBps());
        result.put("topFactors", readJsonValue(prediction.getTopFactorsJson(), List.of()));
        result.put("riskFlags", readJsonValue(prediction.getRiskFlagsJson(), List.of()));
        result.put("backtestSummary", readJsonValue(prediction.getBacktestSummaryJson(), Map.of()));
        return result;
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

    private InvestmentDataQualitySnapshot latestQuality(InvestmentProduct product) {
        return qualityMapper.selectOne(new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                .eq(InvestmentDataQualitySnapshot::getProductType, product.getProductType())
                .eq(InvestmentDataQualitySnapshot::getCode, product.getCode())
                .eq(InvestmentDataQualitySnapshot::getMarket, product.getMarket())
                .orderByDesc(InvestmentDataQualitySnapshot::getEvaluatedAt)
                .last("LIMIT 1"));
    }

    private List<ProductDailyQuote> loadQuotes(InvestmentProduct product) {
        return quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .orderByAsc(ProductDailyQuote::getTradeDate));
    }

    private Object readJsonValue(String value, Object fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private static Map<String, Object> unavailable(String horizonCode) {
        return unavailable(
                horizonCode,
                "MODEL_UNAVAILABLE",
                "暂无有效量化模型"
        );
    }

    private static Map<String, Object> unavailable(String horizonCode,
                                                   String failureCode,
                                                   String userMessage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNAVAILABLE");
        result.put("horizonCode", horizonCode);
        result.put("action", "PAUSE");
        result.put("confidence", "LOW");
        result.put("riskFlags", List.of(failureCode));
        result.put("errorCode", failureCode);
        result.put("userMessage", userMessage);
        return result;
    }

    private static String normalizeHorizon(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分析周期不能为空");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
