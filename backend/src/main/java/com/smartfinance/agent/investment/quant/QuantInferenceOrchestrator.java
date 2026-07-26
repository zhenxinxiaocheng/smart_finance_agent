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
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QuantInferenceOrchestrator {
    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualitySnapshotMapper qualityMapper;
    private final InvestmentHorizonService horizonService;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final QuantStrategyVersionMapper strategyMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantJobMapper jobMapper;
    private final AnalysisServiceClient analysisClient;
    private final WealthService wealthService;
    private final ObjectMapper objectMapper;

    public QuantInferenceOrchestrator(InvestmentAssetMapper assetMapper,
                                      InvestmentProductMapper productMapper,
                                      ProductDailyQuoteMapper quoteMapper,
                                      InvestmentDataQualitySnapshotMapper qualityMapper,
                                      InvestmentHorizonService horizonService,
                                      QuantBenchmarkProfileService benchmarkProfileService,
                                      QuantStrategyVersionMapper strategyMapper,
                                      QuantModelVersionMapper modelMapper,
                                      QuantJobMapper jobMapper,
                                      AnalysisServiceClient analysisClient,
                                      WealthService wealthService,
                                      ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.qualityMapper = qualityMapper;
        this.horizonService = horizonService;
        this.benchmarkProfileService = benchmarkProfileService;
        this.strategyMapper = strategyMapper;
        this.modelMapper = modelMapper;
        this.jobMapper = jobMapper;
        this.analysisClient = analysisClient;
        this.wealthService = wealthService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> refresh(Long userId, Long assetId, String horizonCode) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct product = requireProduct(asset.getProductId());
        ResolvedHorizonProfile profile = horizonService.resolve(userId, assetId);
        HorizonSetting horizon = profile.settings().stream()
                .filter(item -> item.code().equals(normalizeHorizon(horizonCode)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到指定分析周期"));
        InvestmentDataQualitySnapshot quality = latestQuality(product);
        if (quality == null || !"ALLOW".equalsIgnoreCase(quality.getDecision())) {
            return paused("DATA_STALE", "最新数据尚未通过质量检查");
        }
        QuantStrategyVersion strategy = deployedStrategy(userId, assetId, horizon.code());
        if (strategy == null) {
            return paused("MODEL_UNAVAILABLE", "暂无已部署的有效量化模型");
        }
        QuantModelVersion model = modelMapper.selectOne(
                new LambdaQueryWrapper<QuantModelVersion>()
                        .eq(QuantModelVersion::getModelVersion, strategy.getModelVersion())
                        .last("LIMIT 1")
        );
        if (model == null
                || !List.of("VALIDATED", "PAPER_VERIFIED").contains(model.getStatus())
                || model.getHorizonDays() == null
                || horizon.targetHoldingDays() != model.getHorizonDays()) {
            return paused("MODEL_UNAVAILABLE", "暂无已部署的有效量化模型");
        }
        QuantJob reusable = reusableJob(
                userId,
                assetId,
                quality.getDatasetVersion(),
                model.getModelVersion(),
                horizon.code()
        );
        if (reusable != null) {
            return jobView(reusable);
        }
        List<ProductDailyQuote> quotes = loadQuotes(product);
        if (quotes.size() < 2) {
            return paused("INSUFFICIENT_DATA", "有效行情样本不足");
        }
        QuantBenchmarkProfileService.ResolvedBenchmark benchmark =
                benchmarkProfileService.resolve(
                        product.getProductType(),
                        product.getCode(),
                        quotes.get(quotes.size() - 1).getTradeDate(),
                        quotes.get(0).getTradeDate(),
                        quotes.get(quotes.size() - 1).getTradeDate()
                );
        if ("MUTUAL_FUND".equals(product.getProductType()) && !benchmark.available()) {
            return paused("BENCHMARK_UNAVAILABLE", "官方基准数据尚未准备完成");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "PREDICT");
        request.put("datasetVersion", quality.getDatasetVersion());
        request.put("productType", product.getProductType());
        request.put("modelFamily", strategy.getModelFamily());
        request.put("horizonProfileVersion", profile.version());
        request.put("horizonCode", horizon.code());
        request.put("horizonDays", horizon.targetHoldingDays());
        request.put("modelVersion", model.getModelVersion());
        request.put("modelFileHash", model.getArtifactHash());
        request.put("modelConfigVersion", model.getQuantConfigVersion());
        request.put("modelStatus", model.getStatus());
        request.put("strategyVersion", strategy.getStrategyVersion());
        if (benchmark.available()) {
            request.put("benchmarkCode", benchmark.benchmarkCode());
            request.put("benchmarkProfileVersion", benchmark.sourceVersion());
            request.put("benchmarkRecords", benchmark.records());
        }
        WealthOverviewResponse wealth = wealthService.overview(userId);
        BigDecimal totalAssets = wealth == null ? null : wealth.getTotalAssets();
        ProductDailyQuote latest = quotes.get(quotes.size() - 1);
        request.put("currentWeight", PortfolioWeightCalculator.calculate(
                asset.getQuantity(),
                latest.getClosePrice(),
                totalAssets
        ));
        request.put("records", QuantMarketRecords.fromQuotes(
                quotes,
                product.getProductType()
        ));

        Map<String, Object> remote = analysisClient.createQuantJob(request);
        QuantJob job = new QuantJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setExternalJobId(requiredText(remote, "jobId"));
        job.setJobType("PREDICT");
        job.setStatus(String.valueOf(remote.getOrDefault("status", "QUEUED")));
        job.setDatasetVersion(quality.getDatasetVersion());
        job.setFeatureSetVersion(model.getFeatureSetVersion());
        job.setQuantConfigVersion(model.getQuantConfigVersion());
        job.setProductType(product.getProductType());
        job.setMetricsJson(writeJson(Map.of()));
        job.setModelVersion(model.getModelVersion());
        job.setStrategyVersion(strategy.getStrategyVersion());
        job.setHorizonProfileVersion(profile.version());
        job.setHorizonCode(horizon.code());
        job.setHorizonDays(horizon.targetHoldingDays());
        jobMapper.insert(job);
        return jobView(job);
    }

    private QuantStrategyVersion deployedStrategy(Long userId,
                                                  Long assetId,
                                                  String horizonCode) {
        QuantStrategyVersion champion = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, userId)
                        .eq(QuantStrategyVersion::getAssetId, assetId)
                        .eq(QuantStrategyVersion::getHorizonCode, horizonCode)
                        .eq(QuantStrategyVersion::getDeploymentRole, "CHAMPION")
                        .eq(QuantStrategyVersion::getStatus, "CHAMPION")
                        .orderByDesc(QuantStrategyVersion::getActivatedAt)
                        .last("LIMIT 1")
        );
        if (champion != null) {
            return champion;
        }
        return strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, userId)
                        .eq(QuantStrategyVersion::getAssetId, assetId)
                        .eq(QuantStrategyVersion::getHorizonCode, horizonCode)
                        .eq(QuantStrategyVersion::getDeploymentRole, "CHALLENGER")
                        .eq(QuantStrategyVersion::getStatus, "PAPER")
                        .orderByDesc(QuantStrategyVersion::getActivatedAt)
                        .last("LIMIT 1")
        );
    }

    private QuantJob reusableJob(Long userId,
                                 Long assetId,
                                 String datasetVersion,
                                 String modelVersion,
                                 String horizonCode) {
        return jobMapper.selectOne(new LambdaQueryWrapper<QuantJob>()
                .eq(QuantJob::getUserId, userId)
                .eq(QuantJob::getAssetId, assetId)
                .eq(QuantJob::getJobType, "PREDICT")
                .eq(QuantJob::getDatasetVersion, datasetVersion)
                .eq(QuantJob::getModelVersion, modelVersion)
                .eq(QuantJob::getHorizonCode, horizonCode)
                .in(QuantJob::getStatus, "QUEUED", "RUNNING", "SUCCEEDED")
                .orderByDesc(QuantJob::getCreatedAt)
                .last("LIMIT 1"));
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
        return qualityMapper.selectOne(
                new LambdaQueryWrapper<InvestmentDataQualitySnapshot>()
                        .eq(InvestmentDataQualitySnapshot::getProductType, product.getProductType())
                        .eq(InvestmentDataQualitySnapshot::getCode, product.getCode())
                        .eq(InvestmentDataQualitySnapshot::getMarket, product.getMarket())
                        .orderByDesc(InvestmentDataQualitySnapshot::getEvaluatedAt)
                        .last("LIMIT 1")
        );
    }

    private List<ProductDailyQuote> loadQuotes(InvestmentProduct product) {
        List<ProductDailyQuote> all = quoteMapper.selectList(
                new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, product.getId())
                        .orderByAsc(ProductDailyQuote::getTradeDate)
        );
        Map<java.time.LocalDate, ProductDailyQuote> byDate = new LinkedHashMap<>();
        for (ProductDailyQuote quote : all) {
            byDate.put(quote.getTradeDate(), quote);
        }
        return new ArrayList<>(byDate.values());
    }

    private Map<String, Object> jobView(QuantJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getExternalJobId());
        result.put("type", job.getJobType());
        result.put("status", job.getStatus());
        result.put("assetId", job.getAssetId());
        result.put("modelVersion", job.getModelVersion());
        result.put("strategyVersion", job.getStrategyVersion());
        result.put("horizonCode", job.getHorizonCode());
        return result;
    }

    private static Map<String, Object> paused(String errorCode, String userMessage) {
        return Map.of(
                "status", "PAUSED",
                "action", "PAUSE",
                "errorCode", errorCode,
                "userMessage", userMessage
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("量化任务保存失败", exception);
        }
    }

    private static String requiredText(Map<String, Object> value, String key) {
        String result = text(value.get(key));
        if (result == null) {
            throw new IllegalStateException("量化服务未返回任务编号");
        }
        return result;
    }

    private static String normalizeHorizon(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分析周期不能为空");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }
}
