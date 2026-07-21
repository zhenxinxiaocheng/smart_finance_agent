package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.entity.InvestmentAccount;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentCashBalance;
import com.smartfinance.agent.investment.entity.InvestmentDataQualitySnapshot;
import com.smartfinance.agent.investment.entity.InvestmentPosition;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAccountMapper;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentCashBalanceMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataQualitySnapshotMapper;
import com.smartfinance.agent.investment.mapper.InvestmentPositionMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class QuantServiceImpl implements QuantService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualitySnapshotMapper qualityMapper;
    private final InvestmentHorizonService horizonService;
    private final AnalysisServiceClient analysisClient;
    private final QuantJobMapper jobMapper;
    private final QuantFeatureSetMapper featureSetMapper;
    private final QuantTrainingRunMapper trainingRunMapper;
    private final QuantBacktestRunMapper backtestRunMapper;
    private final QuantPredictionMapper predictionMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantStrategyVersionMapper strategyMapper;
    private final InvestmentAccountMapper accountMapper;
    private final InvestmentCashBalanceMapper cashBalanceMapper;
    private final InvestmentPositionMapper positionMapper;
    private final WealthService wealthService;
    private final PaperTradingService paperTradingService;
    private final QuantPaperOrderMapper paperOrderMapper;
    private final QuantPaperFillMapper paperFillMapper;
    private final QuantPaperProperties paperProperties;
    private final ObjectMapper objectMapper;

    public QuantServiceImpl(InvestmentAssetMapper assetMapper,
                            InvestmentProductMapper productMapper,
                            ProductDailyQuoteMapper quoteMapper,
                            InvestmentDataQualitySnapshotMapper qualityMapper,
                            InvestmentHorizonService horizonService,
                            AnalysisServiceClient analysisClient,
                            QuantJobMapper jobMapper,
                            QuantFeatureSetMapper featureSetMapper,
                            QuantTrainingRunMapper trainingRunMapper,
                            QuantBacktestRunMapper backtestRunMapper,
                            QuantPredictionMapper predictionMapper,
                            QuantModelVersionMapper modelMapper,
                            QuantStrategyVersionMapper strategyMapper,
                            InvestmentAccountMapper accountMapper,
                            InvestmentCashBalanceMapper cashBalanceMapper,
                            InvestmentPositionMapper positionMapper,
                            WealthService wealthService,
                            PaperTradingService paperTradingService,
                            QuantPaperOrderMapper paperOrderMapper,
                            QuantPaperFillMapper paperFillMapper,
                            QuantPaperProperties paperProperties,
                            ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.qualityMapper = qualityMapper;
        this.horizonService = horizonService;
        this.analysisClient = analysisClient;
        this.jobMapper = jobMapper;
        this.featureSetMapper = featureSetMapper;
        this.trainingRunMapper = trainingRunMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.predictionMapper = predictionMapper;
        this.modelMapper = modelMapper;
        this.strategyMapper = strategyMapper;
        this.accountMapper = accountMapper;
        this.cashBalanceMapper = cashBalanceMapper;
        this.positionMapper = positionMapper;
        this.wealthService = wealthService;
        this.paperTradingService = paperTradingService;
        this.paperOrderMapper = paperOrderMapper;
        this.paperFillMapper = paperFillMapper;
        this.paperProperties = paperProperties;
        this.objectMapper = objectMapper;
    }

    @Override
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
        if (quality == null || quality.getDatasetVersion() == null) return unavailable(normalizedHorizon);
        boolean stableCache = "BLOCKED".equalsIgnoreCase(quality.getQualityStatus())
                || !"ALLOW".equalsIgnoreCase(quality.getDecision());
        LambdaQueryWrapper<QuantPrediction> query = new LambdaQueryWrapper<QuantPrediction>()
                .eq(QuantPrediction::getUserId, userId)
                .eq(QuantPrediction::getAssetId, assetId)
                .eq(QuantPrediction::getHorizonCode, normalizedHorizon)
                .eq(QuantPrediction::getHorizonDays, horizon.targetHoldingDays())
                .eq(QuantPrediction::getHorizonProfileVersion, profile.version())
                .eq(!stableCache, QuantPrediction::getDatasetVersion, quality.getDatasetVersion())
                .orderByDesc(QuantPrediction::getAsOfDate)
                .orderByDesc(QuantPrediction::getCreatedAt)
                .last("LIMIT 1");
        QuantPrediction prediction = predictionMapper.selectOne(query);
        if (prediction == null) return unavailable(normalizedHorizon);
        Map<String, Object> result = predictionView(prediction);
        if (stableCache) {
            result.put("status", "STABLE_CACHE");
            result.put("currentDatasetVersion", quality.getDatasetVersion());
            result.put("userMessage", "最新数据正在后台校验，当前继续使用最近一次可靠模型结果。");
        }
        return result;
    }

    @Override
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
        if (quality == null || "BLOCKED".equalsIgnoreCase(quality.getQualityStatus())
                || !"ALLOW".equalsIgnoreCase(quality.getDecision())) {
            return blockedJob(userId, assetId, profile, horizon,
                    quality == null ? null : quality.getDatasetVersion());
        }
        List<ProductDailyQuote> quotes = loadQuotes(product);
        if (quotes.isEmpty()) {
            return blockedJob(userId, assetId, profile, horizon, quality.getDatasetVersion());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "TRAIN_PREDICT");
        request.put("datasetVersion", quality.getDatasetVersion());
        request.put("productType", product.getProductType());
        request.put("horizonProfileVersion", profile.version());
        request.put("horizonCode", horizon.code());
        request.put("horizonDays", horizon.targetHoldingDays());
        request.put("benchmarkCode", "CASH_CNY");
        WealthOverviewResponse wealth = wealthService.overview(userId);
        ProductDailyQuote latestQuote = quotes.get(quotes.size() - 1);
        request.put("currentWeight", PortfolioWeightCalculator.calculate(
                asset.getQuantity(), latestQuote.getClosePrice(), wealth.getTotalAssets()));
        request.put("records", quoteRecords(quotes, product.getProductType()));
        Map<String, Object> remote = analysisClient.createQuantJob(request);
        QuantJob job = new QuantJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setExternalJobId(requiredText(remote, "jobId"));
        job.setJobType("TRAIN_PREDICT");
        job.setStatus(String.valueOf(remote.getOrDefault("status", "QUEUED")));
        job.setDatasetVersion(quality.getDatasetVersion());
        job.setHorizonProfileVersion(profile.version());
        job.setHorizonCode(horizon.code());
        job.setHorizonDays(horizon.targetHoldingDays());
        jobMapper.insert(job);
        persistTrainingSubmission(job, remote, product.getProductType());
        return jobView(job);
    }

    @Override
    @Transactional
    public Map<String, Object> job(Long userId, String jobId) {
        QuantJob job = jobMapper.selectOne(new LambdaQueryWrapper<QuantJob>()
                .eq(QuantJob::getUserId, userId)
                .eq(QuantJob::getExternalJobId, jobId)
                .last("LIMIT 1"));
        if (job == null) throw new IllegalArgumentException("量化任务不存在");
        if (List.of("SUCCEEDED", "FAILED", "BLOCKED").contains(job.getStatus())) {
            return jobView(job);
        }
        Map<String, Object> remote = analysisClient.quantJob(jobId);
        String status = String.valueOf(remote.getOrDefault("status", job.getStatus()));
        job.setStatus(status);
        if ("RUNNING".equals(status) && job.getStartedAt() == null) job.setStartedAt(LocalDateTime.now());
        Object rawResult = remote.get("result");
        if (rawResult instanceof Map<?, ?> result) {
            job.setResultJson(writeJson(result));
            updateJobVersions(job, result);
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        updateTrainingRun(job, rawResult);
        if ("SUCCEEDED".equals(status) && rawResult instanceof Map<?, ?> result) {
            persistResult(job, castMap(result));
        }
        return jobView(job);
    }

    @Override
    public Map<String, Object> strategyStatus(Long userId) {
        List<QuantStrategyVersion> strategies = strategyMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT 20"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", strategies.isEmpty() ? "NOT_READY" : "READY");
        result.put("strategies", strategies.stream().map(item -> Map.of(
                "strategyVersion", item.getStrategyVersion(),
                "modelVersion", item.getModelVersion(),
                "productType", item.getProductType(),
                "status", item.getStatus()
        )).toList());
        return result;
    }

    @Override
    public Map<String, Object> paperAccount(Long userId) {
        InvestmentAccount account = accountMapper.selectOne(new LambdaQueryWrapper<InvestmentAccount>()
                .eq(InvestmentAccount::getUserId, userId)
                .eq(InvestmentAccount::getAccountType, "PAPER")
                .orderByDesc(InvestmentAccount::getUpdatedAt)
                .last("LIMIT 1"));
        if (account == null) {
            return Map.of("status", "NOT_STARTED", "cash", List.of(), "positions", List.of());
        }
        List<InvestmentCashBalance> cash = cashBalanceMapper.selectList(
                new LambdaQueryWrapper<InvestmentCashBalance>()
                        .eq(InvestmentCashBalance::getAccountId, account.getId()));
        List<InvestmentPosition> positions = positionMapper.selectList(
                new LambdaQueryWrapper<InvestmentPosition>()
                        .eq(InvestmentPosition::getAccountId, account.getId()));
        List<QuantPaperOrder> orders = paperOrderMapper.selectList(
                new LambdaQueryWrapper<QuantPaperOrder>()
                        .eq(QuantPaperOrder::getAccountId, account.getId())
                        .orderByDesc(QuantPaperOrder::getSubmittedAt)
                        .last("LIMIT " + paperProperties.getAccountOrderHistoryLimit()));
        List<Long> orderIds = orders.stream().map(QuantPaperOrder::getId).toList();
        List<QuantPaperFill> fills = orderIds.isEmpty() ? List.of() : paperFillMapper.selectList(
                new LambdaQueryWrapper<QuantPaperFill>()
                        .in(QuantPaperFill::getOrderId, orderIds)
                        .orderByDesc(QuantPaperFill::getFillDate));
        BigDecimal cashCny = cash.stream().filter(item -> "CNY".equals(item.getCurrency()))
                .map(InvestmentCashBalance::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal marketValue = positions.stream().map(InvestmentPosition::getMarketValueCny)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal equity = cashCny.add(marketValue);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ACTIVE");
        result.put("accountId", account.getId());
        result.put("accountName", account.getAccountName());
        result.put("baseCurrency", account.getBaseCurrency());
        result.put("initialCapitalCny", paperProperties.getInitialCashCny());
        result.put("totalEquityCny", equity);
        result.put("totalReturn", paperProperties.getInitialCashCny().signum() <= 0 ? BigDecimal.ZERO
                : equity.divide(paperProperties.getInitialCashCny(), 10, java.math.RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE));
        result.put("paperTradingDays", fills.stream().map(QuantPaperFill::getFillDate).distinct().count());
        result.put("cash", cash.stream().map(item -> Map.of(
                "currency", item.getCurrency(), "balance", item.getBalance())).toList());
        result.put("positions", positions.stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("productId", item.getProductId());
            value.put("quantity", item.getQuantity());
            value.put("averageCost", item.getAverageCost());
            value.put("latestPrice", item.getLatestPrice());
            value.put("marketValueCny", item.getMarketValueCny());
            value.put("unrealizedPnlCny", item.getUnrealizedPnlCny());
            return value;
        }).toList());
        result.put("orders", orders.stream().map(item -> Map.of(
                "orderId", item.getId(),
                "productId", item.getProductId(),
                "strategyVersion", item.getStrategyVersion(),
                "side", item.getSide(),
                "quantity", item.getQuantity(),
                "status", item.getStatus(),
                "submittedAt", item.getSubmittedAt()
        )).toList());
        return result;
    }

    private Map<String, Object> blockedJob(Long userId, Long assetId,
                                           ResolvedHorizonProfile profile,
                                           HorizonSetting horizon,
                                           String datasetVersion) {
        QuantJob job = new QuantJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setExternalJobId(UUID.randomUUID().toString().replace("-", ""));
        job.setJobType("TRAIN_PREDICT");
        job.setStatus("BLOCKED");
        job.setDatasetVersion(datasetVersion);
        job.setHorizonProfileVersion(profile.version());
        job.setHorizonCode(horizon.code());
        job.setHorizonDays(horizon.targetHoldingDays());
        job.setUserMessage("当前数据尚未达到模型训练要求，系统不会生成交易建议。");
        job.setResultJson(writeJson(Map.of(
                "action", "NO_TRADE", "riskFlags", List.of("DATA_NOT_READY"))));
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.insert(job);
        return jobView(job);
    }

    private void persistResult(QuantJob job, Map<String, Object> result) {
        String featureSetVersion = text(result.get("featureSetVersion"));
        String modelVersion = text(result.get("modelVersion"));
        String strategyVersion = text(result.get("strategyVersion"));
        if (featureSetVersion == null) return;
        persistFeatureSet(job, result, featureSetVersion);
        QuantPrediction existing = predictionMapper.selectOne(new LambdaQueryWrapper<QuantPrediction>()
                .eq(QuantPrediction::getUserId, job.getUserId())
                .eq(QuantPrediction::getAssetId, job.getAssetId())
                .eq(QuantPrediction::getDatasetVersion, job.getDatasetVersion())
                .eq(QuantPrediction::getHorizonCode, job.getHorizonCode())
                .eq(modelVersion != null, QuantPrediction::getModelVersion, modelVersion)
                .last("LIMIT 1"));
        if (existing != null) return;
        QuantPrediction prediction = new QuantPrediction();
        prediction.setUserId(job.getUserId());
        prediction.setAssetId(job.getAssetId());
        prediction.setDatasetVersion(job.getDatasetVersion());
        prediction.setFeatureSetVersion(featureSetVersion);
        prediction.setModelVersion(modelVersion);
        prediction.setStrategyVersion(strategyVersion);
        prediction.setHorizonProfileVersion(job.getHorizonProfileVersion());
        prediction.setHorizonCode(job.getHorizonCode());
        prediction.setHorizonDays(job.getHorizonDays());
        prediction.setAsOfDate(LocalDate.parse(String.valueOf(result.get("asOfDate"))));
        prediction.setProbabilityPositiveExcess(decimal(result.get("probabilityPositiveExcess")));
        prediction.setExpectedExcessReturn(decimal(result.get("expectedExcessReturn")));
        if (result.get("predictionInterval") instanceof List<?> interval && interval.size() == 2) {
            prediction.setIntervalLower(decimal(interval.get(0)));
            prediction.setIntervalUpper(decimal(interval.get(1)));
        }
        prediction.setConfidence(String.valueOf(result.getOrDefault("confidence", "LOW")));
        prediction.setAction(String.valueOf(result.getOrDefault("action", "NO_TRADE")));
        prediction.setTargetWeight(Objects.requireNonNullElse(decimal(result.get("targetWeight")), BigDecimal.ZERO));
        prediction.setMarketRegime(text(result.get("marketRegime")));
        prediction.setBenchmarkCode(text(result.get("benchmarkCode")));
        prediction.setRoundTripCostBps(decimal(result.get("roundTripCostBps")));
        prediction.setTopFactorsJson(writeJson(result.getOrDefault("topFactors", List.of())));
        prediction.setRiskFlagsJson(writeJson(result.getOrDefault("riskFlags", List.of())));
        prediction.setBacktestSummaryJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
        predictionMapper.insert(prediction);
        if (modelVersion != null) {
            persistModelAndStrategy(job, result, modelVersion, strategyVersion);
            persistBacktestRun(job, result, modelVersion, strategyVersion);
            InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
            paperTradingService.queueValidatedPrediction(
                    job.getUserId(), product, prediction, String.valueOf(result.get("modelStatus")));
        }
    }

    private void persistFeatureSet(QuantJob job, Map<String, Object> result, String featureSetVersion) {
        if (featureSetMapper.selectCount(new LambdaQueryWrapper<QuantFeatureSet>()
                .eq(QuantFeatureSet::getFeatureSetVersion, featureSetVersion)) != 0) return;
        InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
        QuantFeatureSet featureSet = new QuantFeatureSet();
        featureSet.setFeatureSetVersion(featureSetVersion);
        featureSet.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
        featureSet.setProductType(product.getProductType());
        featureSet.setSchemaJson(writeJson(result.getOrDefault("featureSchema", List.of())));
        featureSet.setArtifactUri(text(result.get("featureArtifactUri")));
        featureSet.setArtifactHash(text(result.get("featureArtifactHash")));
        featureSetMapper.insert(featureSet);
    }

    private void persistTrainingSubmission(QuantJob job, Map<String, Object> remote, String productType) {
        QuantTrainingRun run = new QuantTrainingRun();
        run.setExternalJobId(job.getExternalJobId());
        run.setDatasetVersion(job.getDatasetVersion());
        run.setQuantConfigVersion(String.valueOf(remote.get("configVersion")));
        run.setProductType(productType);
        run.setHorizonDays(job.getHorizonDays());
        run.setStatus(job.getStatus());
        run.setMetricsJson(writeJson(Map.of()));
        run.setStartedAt(LocalDateTime.now());
        trainingRunMapper.insert(run);
    }

    private void persistBacktestRun(QuantJob job, Map<String, Object> result,
                                    String modelVersion, String strategyVersion) {
        if (backtestRunMapper.selectCount(new LambdaQueryWrapper<QuantBacktestRun>()
                .eq(QuantBacktestRun::getModelVersion, modelVersion)
                .eq(QuantBacktestRun::getDatasetVersion, job.getDatasetVersion())
                .eq(QuantBacktestRun::getHorizonDays, job.getHorizonDays())) > 0) return;
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

    private void updateTrainingRun(QuantJob job, Object rawResult) {
        QuantTrainingRun run = trainingRunMapper.selectOne(new LambdaQueryWrapper<QuantTrainingRun>()
                .eq(QuantTrainingRun::getExternalJobId, job.getExternalJobId())
                .last("LIMIT 1"));
        if (run == null) return;
        run.setStatus(job.getStatus());
        run.setFeatureSetVersion(job.getFeatureSetVersion());
        if (rawResult instanceof Map<?, ?> result) {
            Object metrics = result.get("backtestSummary");
            run.setMetricsJson(writeJson(metrics == null ? Map.of() : metrics));
        }
        if (List.of("SUCCEEDED", "FAILED", "BLOCKED").contains(job.getStatus())) {
            run.setFinishedAt(LocalDateTime.now());
        }
        trainingRunMapper.updateById(run);
    }

    private void persistModelAndStrategy(QuantJob job, Map<String, Object> result,
                                         String modelVersion, String strategyVersion) {
        if (modelMapper.selectCount(new LambdaQueryWrapper<QuantModelVersion>()
                .eq(QuantModelVersion::getModelVersion, modelVersion)) == 0) {
            QuantModelVersion model = new QuantModelVersion();
            model.setModelVersion(modelVersion);
            model.setFeatureSetVersion(String.valueOf(result.get("featureSetVersion")));
            model.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
            InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
            model.setProductType(product.getProductType());
            model.setHorizonDays(job.getHorizonDays());
            model.setStatus(String.valueOf(result.getOrDefault("modelStatus", "DRAFT")));
            model.setArtifactUri("analysis-service://quant-models/" + modelVersion);
            model.setArtifactHash(String.valueOf(result.get("modelFileHash")));
            model.setMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
            model.setTrainedAt(LocalDateTime.now());
            modelMapper.insert(model);
        }
        if (strategyVersion != null && strategyMapper.selectCount(new LambdaQueryWrapper<QuantStrategyVersion>()
                .eq(QuantStrategyVersion::getStrategyVersion, strategyVersion)) == 0) {
            QuantStrategyVersion strategy = new QuantStrategyVersion();
            strategy.setStrategyVersion(strategyVersion);
            strategy.setModelVersion(modelVersion);
            InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
            strategy.setProductType(product.getProductType());
            boolean validated = "VALIDATED".equals(result.get("modelStatus"));
            strategy.setStatus(validated ? "PAPER" : "DRAFT");
            strategy.setValidationMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
            if (validated) strategy.setActivatedAt(LocalDateTime.now());
            strategyMapper.insert(strategy);
        }
    }

    private List<ProductDailyQuote> loadQuotes(InvestmentProduct product) {
        List<ProductDailyQuote> all = quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .orderByAsc(ProductDailyQuote::getTradeDate));
        Map<LocalDate, ProductDailyQuote> byDate = new LinkedHashMap<>();
        for (ProductDailyQuote quote : all) byDate.put(quote.getTradeDate(), quote);
        return new ArrayList<>(byDate.values());
    }

    private static List<Map<String, Object>> quoteRecords(List<ProductDailyQuote> quotes, String productType) {
        return quotes.stream().map(quote -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("data_date", quote.getTradeDate().toString());
            if ("MUTUAL_FUND".equals(productType)) item.put("nav", quote.getClosePrice());
            else {
                item.put("open", quote.getOpenPrice());
                item.put("high", quote.getHighPrice());
                item.put("low", quote.getLowPrice());
                item.put("close", quote.getClosePrice());
                item.put("volume", quote.getVolume());
            }
            return item;
        }).toList();
    }

    private InvestmentAsset requireAsset(Long userId, Long assetId) {
        InvestmentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getId, assetId)
                .eq(InvestmentAsset::getUserId, userId)
                .last("LIMIT 1"));
        if (asset == null) throw new IllegalArgumentException("投资资产不存在");
        return asset;
    }

    private InvestmentProduct requireProduct(Long productId) {
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
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

    private Map<String, Object> jobView(QuantJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getExternalJobId());
        result.put("type", job.getJobType());
        result.put("status", job.getStatus());
        result.put("assetId", job.getAssetId());
        result.put("datasetVersion", job.getDatasetVersion());
        result.put("featureSetVersion", job.getFeatureSetVersion());
        result.put("modelVersion", job.getModelVersion());
        result.put("strategyVersion", job.getStrategyVersion());
        result.put("horizonCode", job.getHorizonCode());
        result.put("horizonDays", job.getHorizonDays());
        result.put("userMessage", job.getUserMessage());
        result.put("result", readJson(job.getResultJson()));
        return result;
    }

    private Map<String, Object> predictionView(QuantPrediction prediction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "READY");
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
        result.put("marketRegime", prediction.getMarketRegime());
        result.put("benchmarkCode", prediction.getBenchmarkCode());
        result.put("roundTripCostBps", prediction.getRoundTripCostBps());
        result.put("topFactors", readJsonValue(prediction.getTopFactorsJson(), List.of()));
        result.put("riskFlags", readJsonValue(prediction.getRiskFlagsJson(), List.of()));
        result.put("backtestSummary", readJsonValue(prediction.getBacktestSummaryJson(), Map.of()));
        return result;
    }

    private static Map<String, Object> unavailable(String horizonCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNAVAILABLE");
        result.put("horizonCode", horizonCode);
        result.put("action", "NO_TRADE");
        result.put("confidence", "LOW");
        result.put("riskFlags", List.of("MODEL_UNAVAILABLE"));
        result.put("userMessage", "量化模型尚未完成训练，当前不生成交易建议。");
        return result;
    }

    private void updateJobVersions(QuantJob job, Map<?, ?> result) {
        job.setFeatureSetVersion(text(result.get("featureSetVersion")));
        job.setModelVersion(text(result.get("modelVersion")));
        job.setStrategyVersion(text(result.get("strategyVersion")));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("量化结果保存失败", e);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of("action", "NO_TRADE", "riskFlags", List.of("RESULT_UNAVAILABLE"));
        }
    }

    private Object readJsonValue(String value, Object fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    private static String normalizeHorizon(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("分析周期不能为空");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requiredText(Map<String, Object> value, String key) {
        String result = text(value.get(key));
        if (result == null) throw new IllegalStateException("量化服务未返回任务编号");
        return result;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
