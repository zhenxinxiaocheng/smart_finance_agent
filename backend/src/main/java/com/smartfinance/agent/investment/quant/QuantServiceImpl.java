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
import java.util.Set;
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
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final QuantJobMapper jobMapper;
    private final QuantFeatureSetMapper featureSetMapper;
    private final QuantTrainingRunMapper trainingRunMapper;
    private final QuantBacktestRunMapper backtestRunMapper;
    private final QuantPredictionMapper predictionMapper;
    private final QuantExperimentMapper experimentMapper;
    private final QuantResearchUniverseMapper universeMapper;
    private final QuantUniverseMembershipMapper membershipMapper;
    private final QuantValidationReportMapper validationReportMapper;
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
                            QuantBenchmarkProfileService benchmarkProfileService,
                            QuantJobMapper jobMapper,
                            QuantFeatureSetMapper featureSetMapper,
                            QuantTrainingRunMapper trainingRunMapper,
                            QuantBacktestRunMapper backtestRunMapper,
                            QuantPredictionMapper predictionMapper,
                            QuantExperimentMapper experimentMapper,
                            QuantResearchUniverseMapper universeMapper,
                            QuantUniverseMembershipMapper membershipMapper,
                            QuantValidationReportMapper validationReportMapper,
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
        this.benchmarkProfileService = benchmarkProfileService;
        this.jobMapper = jobMapper;
        this.featureSetMapper = featureSetMapper;
        this.trainingRunMapper = trainingRunMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.predictionMapper = predictionMapper;
        this.experimentMapper = experimentMapper;
        this.universeMapper = universeMapper;
        this.membershipMapper = membershipMapper;
        this.validationReportMapper = validationReportMapper;
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
        if (quality == null || quality.getDatasetVersion() == null) {
            return unavailable(normalizedHorizon, "INSUFFICIENT_DATA",
                    "研究数据不足，当前不生成交易建议。");
        }
        if ("BLOCKED".equalsIgnoreCase(quality.getQualityStatus())
                || !"ALLOW".equalsIgnoreCase(quality.getDecision())) {
            return unavailable(normalizedHorizon, "DATA_STALE",
                    "最新研究数据尚未通过质量校验，当前不使用旧模型冒充有效结果。");
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
        if (result == null) return unavailable(normalizedHorizon);
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

    @Override
    @Transactional
    public Map<String, Object> refresh(Long userId, Long assetId, String horizonCode) {
        return refreshInternal(
                userId, assetId, null, horizonCode, null, null, "VALIDATED_ENSEMBLE", Map.of());
    }

    @Override
    @Transactional
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 Map<String, Object> parameters) {
        return refreshExperiment(
                userId,
                assetId,
                null,
                horizonCode,
                experimentFingerprint,
                modelFamily,
                "VALIDATED_ENSEMBLE",
                parameters
        );
    }

    @Override
    @Transactional
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 String algorithm,
                                                 Map<String, Object> parameters) {
        return refreshExperiment(
                userId, assetId, null, horizonCode, experimentFingerprint,
                modelFamily, algorithm, parameters);
    }

    @Override
    @Transactional
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 Long universeId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 String algorithm,
                                                 Map<String, Object> parameters) {
        if (experimentFingerprint == null || !experimentFingerprint.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("实验指纹格式不正确");
        }
        Map<String, Object> result = refreshInternal(
                userId,
                assetId,
                universeId,
                horizonCode,
                experimentFingerprint,
                modelFamily,
                algorithm,
                parameters == null ? Map.of() : Map.copyOf(parameters)
        );
        return result;
    }

    private Map<String, Object> refreshInternal(Long userId,
                                                Long assetId,
                                                Long universeId,
                                                String horizonCode,
                                                String experimentFingerprint,
                                                String requestedModelFamily,
                                                String algorithm,
                                                Map<String, Object> experimentParameters) {
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
                    quality == null ? null : quality.getDatasetVersion(),
                    quality == null ? "INSUFFICIENT_DATA" : "DATA_STALE",
                    experimentFingerprint);
        }
        List<ProductDailyQuote> quotes = loadQuotes(product);
        if (quotes.isEmpty()) {
            return blockedJob(
                    userId, assetId, profile, horizon, quality.getDatasetVersion(),
                    "INSUFFICIENT_DATA", experimentFingerprint);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "TRAIN_PREDICT");
        request.put("datasetVersion", quality.getDatasetVersion());
        request.put("productType", product.getProductType());
        request.put("horizonProfileVersion", profile.version());
        request.put("horizonCode", horizon.code());
        request.put("horizonDays", horizon.targetHoldingDays());
        QuantBenchmarkProfileService.ResolvedBenchmark benchmark = benchmarkProfileService.resolve(
                product.getProductType(),
                product.getCode(),
                quotes.get(quotes.size() - 1).getTradeDate(),
                quotes.get(0).getTradeDate(),
                quotes.get(quotes.size() - 1).getTradeDate()
        );
        if ("MUTUAL_FUND".equals(product.getProductType()) && !benchmark.available()) {
            return blockedJob(
                    userId,
                    assetId,
                    profile,
                    horizon,
                    quality.getDatasetVersion(),
                    "BENCHMARK_UNAVAILABLE",
                    experimentFingerprint
            );
        }
        String resolvedModelFamily = benchmark.available()
                ? benchmark.modelFamily()
                : defaultModelFamily(product.getProductType());
        request.put("modelFamily", resolvedModelFamily);
        if (requestedModelFamily != null && !requestedModelFamily.isBlank()) {
            if (benchmark.available() && !requestedModelFamily.equals(benchmark.modelFamily())) {
                throw new IllegalArgumentException("研究资产与所选模型家族不匹配");
            }
            request.put("modelFamily", requestedModelFamily);
        }
        request.put("algorithm", algorithm);
        if (experimentFingerprint != null) {
            request.put("experimentFingerprint", experimentFingerprint);
            request.put("experimentParameters", experimentParameters);
        }
        if (benchmark.available()) {
            request.put("benchmarkCode", benchmark.benchmarkCode());
            request.put("benchmarkProfileVersion", benchmark.sourceVersion());
            request.put("benchmarkRecords", benchmark.records());
        }
        WealthOverviewResponse wealth = wealthService.overview(userId);
        ProductDailyQuote latestQuote = quotes.get(quotes.size() - 1);
        request.put("currentWeight", PortfolioWeightCalculator.calculate(
                asset.getQuantity(), latestQuote.getClosePrice(), wealth.getTotalAssets()));
        request.put("records", quoteRecords(quotes, product.getProductType()));
        if (universeId != null) {
            request.put("researchUniverseVersion",
                    researchContextVersion(userId, assetId, universeId));
            request.put("universeRecords", loadUniverseRecords(
                    universeId, product.getId(), quotes.get(quotes.size() - 1).getTradeDate()));
        }
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
        job.setExperimentFingerprint(experimentFingerprint);
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
        job.setErrorCode(text(remote.get("errorCode")));
        job.setErrorSummary(text(remote.get("errorSummary")));
        job.setUserMessage(text(remote.get("userMessage")));
        if ("RUNNING".equals(status) && job.getStartedAt() == null) job.setStartedAt(LocalDateTime.now());
        Object rawResult = remote.get("result");
        if (rawResult instanceof Map<?, ?> result) {
            job.setResultJson(writeJson(result));
            updateJobVersions(job, result);
            applyValidationOutcome(job, result);
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        updateTrainingRun(job, rawResult);
        if ("SUCCEEDED".equals(status) && rawResult instanceof Map<?, ?> result) {
            persistResult(job, castMap(result));
        }
        syncExperiment(job, rawResult);
        return jobView(job);
    }

    @Override
    @Transactional
    public Map<String, Object> cancelJob(Long userId, String jobId) {
        QuantJob job = jobMapper.selectOne(new LambdaQueryWrapper<QuantJob>()
                .eq(QuantJob::getUserId, userId)
                .eq(QuantJob::getExternalJobId, jobId)
                .last("LIMIT 1"));
        if (job == null) throw new IllegalArgumentException("量化任务不存在");
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(job.getStatus())) {
            return jobView(job);
        }
        Map<String, Object> remote = analysisClient.cancelQuantJob(jobId);
        job.setStatus(String.valueOf(remote.getOrDefault("status", "CANCELLED")));
        job.setUserMessage(text(remote.get("userMessage")));
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        updateTrainingRun(job, null);
        syncExperiment(job, null);
        return jobView(job);
    }

    @Override
    @Transactional
    public void activatePaperModel(Long userId, String modelVersion) {
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
        if (!List.of("PAPER", "CHAMPION").contains(strategy.getStatus())) {
            strategy.setStatus("PAPER");
            strategy.setActivatedAt(LocalDateTime.now());
            strategyMapper.updateById(strategy);
        }
        InvestmentProduct product = requireProduct(
                requireAsset(userId, prediction.getAssetId()).getProductId());
        paperTradingService.queueValidatedPrediction(
                userId, product, prediction, model.getStatus());
    }

    @Override
    public String researchContextVersion(Long userId, Long assetId, Long universeId) {
        InvestmentAsset asset = requireAsset(userId, assetId);
        InvestmentProduct target = requireProduct(asset.getProductId());
        InvestmentDataQualitySnapshot targetQuality = latestQuality(target);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("targetProductId", target.getId());
        material.put("targetDatasetVersion",
                targetQuality == null ? null : targetQuality.getDatasetVersion());
        if (universeId != null) {
            QuantResearchUniverse universe = universeMapper.selectById(universeId);
            if (universe == null || !Boolean.TRUE.equals(universe.getActive())) {
                throw new IllegalArgumentException("研究资产池不存在或已停用");
            }
            material.put("universeId", universeId);
            material.put("universeDatasetVersion", universe.getDatasetVersion());
            List<Map<String, Object>> members = new ArrayList<>();
            for (QuantUniverseMembership membership : membershipMapper.selectList(
                    new LambdaQueryWrapper<QuantUniverseMembership>()
                            .eq(QuantUniverseMembership::getUniverseId, universeId)
                            .orderByAsc(QuantUniverseMembership::getValidFrom)
                            .orderByAsc(QuantUniverseMembership::getCode))) {
                InvestmentProduct product = membership.getProductId() == null
                        ? null
                        : productMapper.selectById(membership.getProductId());
                InvestmentDataQualitySnapshot quality = product == null ? null : latestQuality(product);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("productId", membership.getProductId());
                member.put("code", membership.getCode());
                member.put("validFrom", membership.getValidFrom());
                member.put("validTo", membership.getValidTo());
                member.put("sourceSnapshot", membership.getSourceSnapshot());
                member.put("datasetVersion", quality == null ? null : quality.getDatasetVersion());
                members.add(member);
            }
            material.put("members", members);
        }
        return QuantResearchCatalog.canonicalHash(material);
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
                                           String datasetVersion,
                                           String errorCode,
                                           String experimentFingerprint) {
        QuantJob job = new QuantJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setExternalJobId(UUID.randomUUID().toString().replace("-", ""));
        job.setJobType("TRAIN_PREDICT");
        job.setStatus("SUCCEEDED");
        job.setExperimentFingerprint(experimentFingerprint);
        job.setErrorCode(errorCode);
        job.setErrorSummary(switch (errorCode) {
            case "DATA_STALE" -> "最新研究数据未通过质量校验";
            case "BENCHMARK_UNAVAILABLE" -> "官方基准数据尚未准备完成";
            default -> "有效训练样本不足";
        });
        job.setDatasetVersion(datasetVersion);
        job.setHorizonProfileVersion(profile.version());
        job.setHorizonCode(horizon.code());
        job.setHorizonDays(horizon.targetHoldingDays());
        job.setUserMessage(switch (errorCode) {
            case "DATA_STALE" -> "最新数据尚未通过质量检查，当前暂停模型训练";
            case "BENCHMARK_UNAVAILABLE" -> "官方基准数据尚未准备完成，当前暂停模型训练";
            default -> "当前有效样本不足，暂不训练模型";
        });
        job.setResultJson(writeJson(Map.of(
                "action", "PAUSE",
                "modelStatus", "DRAFT",
                "riskFlags", List.of(errorCode))));
        job.setFinishedAt(LocalDateTime.now());
        jobMapper.insert(job);
        return jobView(job);
    }

    private void persistResult(QuantJob job, Map<String, Object> result) {
        String featureSetVersion = text(result.get("featureSetVersion"));
        String modelVersion = text(result.get("modelVersion"));
        String strategyVersion = text(result.get("strategyVersion"));
        boolean strictlyValidated = isStrictlyValidated(result);
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
        prediction.setModelFamily(text(result.get("modelFamily")));
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
        prediction.setAction(strictlyValidated
                ? String.valueOf(result.getOrDefault("action", "NO_TRADE"))
                : "NO_TRADE");
        prediction.setTargetWeight(strictlyValidated
                ? Objects.requireNonNullElse(decimal(result.get("targetWeight")), BigDecimal.ZERO)
                : BigDecimal.ZERO);
        prediction.setMarketRegime(text(result.get("marketRegime")));
        prediction.setBenchmarkCode(text(result.get("benchmarkCode")));
        prediction.setRoundTripCostBps(decimal(result.get("roundTripCostBps")));
        prediction.setFeatureVectorJson(writeJson(result.getOrDefault("featureVector", Map.of())));
        prediction.setTopFactorsJson(writeJson(result.getOrDefault("topFactors", List.of())));
        prediction.setRiskFlagsJson(writeJson(result.getOrDefault("riskFlags", List.of())));
        prediction.setBacktestSummaryJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
        predictionMapper.insert(prediction);
        if (modelVersion != null) {
            persistModelAndStrategy(job, result, modelVersion, strategyVersion, strictlyValidated);
            persistValidationReport(job, result, modelVersion);
            persistBacktestRun(job, result, modelVersion, strategyVersion);
            if (strictlyValidated && job.getExperimentFingerprint() == null) {
                InvestmentProduct product = requireProduct(
                        requireAsset(job.getUserId(), job.getAssetId()).getProductId());
                paperTradingService.queueValidatedPrediction(
                        job.getUserId(), product, prediction, String.valueOf(result.get("modelStatus")));
            }
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
                                         String modelVersion, String strategyVersion,
                                         boolean strictlyValidated) {
        boolean autoPaper = strictlyValidated && job.getExperimentFingerprint() == null;
        if (modelMapper.selectCount(new LambdaQueryWrapper<QuantModelVersion>()
                .eq(QuantModelVersion::getModelVersion, modelVersion)) == 0) {
            QuantModelVersion model = new QuantModelVersion();
            model.setModelVersion(modelVersion);
            model.setFeatureSetVersion(String.valueOf(result.get("featureSetVersion")));
            model.setQuantConfigVersion(String.valueOf(result.get("quantConfigVersion")));
            InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
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
        if (strategyVersion != null && strategyMapper.selectCount(new LambdaQueryWrapper<QuantStrategyVersion>()
                .eq(QuantStrategyVersion::getStrategyVersion, strategyVersion)) == 0) {
            QuantStrategyVersion strategy = new QuantStrategyVersion();
            strategy.setStrategyVersion(strategyVersion);
            strategy.setModelVersion(modelVersion);
            InvestmentProduct product = requireProduct(requireAsset(job.getUserId(), job.getAssetId()).getProductId());
            strategy.setProductType(product.getProductType());
            strategy.setStatus(autoPaper ? "PAPER" : "DRAFT");
            strategy.setValidationMetricsJson(writeJson(result.getOrDefault("backtestSummary", Map.of())));
            if (autoPaper) strategy.setActivatedAt(LocalDateTime.now());
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

    private List<Map<String, Object>> loadUniverseRecords(
            Long universeId,
            Long targetProductId,
            LocalDate asOfDate
    ) {
        QuantResearchUniverse universe = universeMapper.selectById(universeId);
        if (universe == null || !Boolean.TRUE.equals(universe.getActive())) {
            throw new IllegalArgumentException("研究资产池不存在或已停用");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (QuantUniverseMembership membership : membershipMapper.selectList(
                new LambdaQueryWrapper<QuantUniverseMembership>()
                        .eq(QuantUniverseMembership::getUniverseId, universeId)
                        .le(QuantUniverseMembership::getValidFrom, asOfDate)
                        .orderByAsc(QuantUniverseMembership::getCode)
                        .orderByAsc(QuantUniverseMembership::getValidFrom))) {
            if (membership.getProductId() == null
                    || membership.getProductId().equals(targetProductId)) {
                continue;
            }
            InvestmentProduct product = productMapper.selectById(membership.getProductId());
            InvestmentDataQualitySnapshot quality = product == null ? null : latestQuality(product);
            if (product == null || quality == null
                    || !"ALLOW".equalsIgnoreCase(quality.getDecision())) {
                continue;
            }
            List<ProductDailyQuote> quotes = loadQuotes(product).stream()
                    .filter(quote -> !quote.getTradeDate().isBefore(membership.getValidFrom()))
                    .filter(quote -> membership.getValidTo() == null
                            || !quote.getTradeDate().isAfter(membership.getValidTo()))
                    .toList();
            if (quotes.size() < 2) continue;
            QuantBenchmarkProfileService.ResolvedBenchmark benchmark =
                    benchmarkProfileService.resolve(
                            product.getProductType(),
                            product.getCode(),
                            quotes.get(quotes.size() - 1).getTradeDate(),
                            quotes.get(0).getTradeDate(),
                            quotes.get(quotes.size() - 1).getTradeDate()
                    );
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seriesId", membership.getCode() + "@" + membership.getValidFrom());
            item.put("code", membership.getCode());
            item.put("productType", product.getProductType());
            item.put("datasetVersion", quality.getDatasetVersion());
            item.put("records", quoteRecords(quotes, product.getProductType()));
            if (benchmark.available()) {
                item.put("benchmarkCode", benchmark.benchmarkCode());
                item.put("benchmarkProfileVersion", benchmark.sourceVersion());
                item.put("benchmarkRecords", benchmark.records());
            }
            result.add(item);
        }
        return result;
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
        result.put("experimentFingerprint", job.getExperimentFingerprint());
        result.put("errorCode", job.getErrorCode());
        result.put("errorSummary", job.getErrorSummary());
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
        result.put("targetWeight", tradable ? prediction.getTargetWeight() : null);
        result.put("recommendedTargetWeight", tradable ? prediction.getTargetWeight() : null);
        result.put("marketRegime", prediction.getMarketRegime());
        result.put("benchmarkCode", prediction.getBenchmarkCode());
        result.put("roundTripCostBps", prediction.getRoundTripCostBps());
        result.put("topFactors", readJsonValue(prediction.getTopFactorsJson(), List.of()));
        result.put("riskFlags", readJsonValue(prediction.getRiskFlagsJson(), List.of()));
        result.put("backtestSummary", readJsonValue(prediction.getBacktestSummaryJson(), Map.of()));
        return result;
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

    private void updateJobVersions(QuantJob job, Map<?, ?> result) {
        job.setFeatureSetVersion(text(result.get("featureSetVersion")));
        job.setModelVersion(text(result.get("modelVersion")));
        job.setStrategyVersion(text(result.get("strategyVersion")));
    }

    private void applyValidationOutcome(QuantJob job, Map<?, ?> result) {
        Object rawReport = result.get("validationReport");
        if (!(rawReport instanceof Map<?, ?> report) || Boolean.TRUE.equals(report.get("passed"))) {
            return;
        }
        List<String> failureCodes = stringList(report.get("failureCodes"));
        String primary = failureCodes.stream()
                .filter(Set.of(
                        "INSUFFICIENT_DATA",
                        "DATA_STALE",
                        "BENCHMARK_UNAVAILABLE"
                )::contains)
                .findFirst()
                .orElse("MODEL_REJECTED");
        job.setErrorCode(primary);
        job.setErrorSummary(failureCodes.isEmpty()
                ? "模型未通过严格验证"
                : "未通过指标：" + String.join("、", failureCodes));
        job.setUserMessage("训练任务已完成，但模型未通过严格验证；不会生成交易金额或模拟订单。");
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
        if (!(rawReport instanceof Map<?, ?> report)) return;
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

    private void syncExperiment(QuantJob job, Object rawResult) {
        var existing = findExperiment(job);
        if (existing.isEmpty()) return;
        QuantExperiment experiment = existing.get();
        experiment.setStatus(job.getStatus());
        experiment.setDatasetVersion(job.getDatasetVersion());
        experiment.setFeatureSetVersion(job.getFeatureSetVersion());
        experiment.setCandidateModelVersion(job.getModelVersion());
        experiment.setErrorCode(job.getErrorCode());
        experiment.setErrorSummary(job.getErrorSummary());
        if (rawResult instanceof Map<?, ?> result) {
            String configVersion = text(result.get("quantConfigVersion"));
            if (configVersion != null) experiment.setQuantConfigVersion(configVersion);
        }
        experiment.setLogsJson(writeJson(Map.of(
                "jobId", job.getExternalJobId(),
                "jobStatus", job.getStatus()
        )));
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(job.getStatus())) {
            experiment.setFinishedAt(Objects.requireNonNullElse(
                    job.getFinishedAt(),
                    LocalDateTime.now()
            ));
        }
        experimentMapper.updateById(experiment);
    }

    private java.util.Optional<QuantExperiment> findExperiment(QuantJob job) {
        if (job.getExperimentFingerprint() == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(experimentMapper.selectOne(
                new LambdaQueryWrapper<QuantExperiment>()
                        .eq(QuantExperiment::getUserId, job.getUserId())
                        .eq(QuantExperiment::getExperimentFingerprint, job.getExperimentFingerprint())
                        .last("LIMIT 1")
        ));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
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

    private static String defaultModelFamily(String productType) {
        return "STOCK".equalsIgnoreCase(productType) ? "A_SHARE_STOCK" : "ACTIVE_FUND";
    }

    private static boolean isStrictlyValidated(Map<String, Object> result) {
        String lifecycle = text(result.get("modelStatus"));
        if (!List.of("VALIDATED", "PAPER_VERIFIED").contains(lifecycle)) return false;
        Object reportValue = result.get("validationReport");
        if (!(reportValue instanceof Map<?, ?> report)) return false;
        return Boolean.TRUE.equals(report.get("passed"));
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
