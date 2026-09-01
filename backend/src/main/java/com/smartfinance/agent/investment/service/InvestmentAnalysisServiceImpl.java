package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.FundClassificationPolicy;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import com.smartfinance.agent.investment.quant.QuantModelMonitor;
import com.smartfinance.agent.investment.quant.QuantModelMonitorMapper;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.investment.quant.QuantStrategyVersion;
import com.smartfinance.agent.investment.quant.QuantStrategyVersionMapper;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class InvestmentAnalysisServiceImpl implements InvestmentAnalysisService {

    private final InvestmentAssetService assetService;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentHorizonService horizonService;
    private final InvestmentHorizonProperties horizonProperties;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final InvestmentAnalysisSnapshotMapper snapshotMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentDataQualityService dataQualityService;
    private final InvestmentSyncWorker syncWorker;
    private final WealthService wealthService;
    private final FinancialProfileMapper financialProfileMapper;
    private final InvestmentAiExplanationService aiExplanationService;
    private final ObjectMapper objectMapper;
    private final InvestmentDataJobService dataJobService;
    private final InvestmentFinancialWarningEngine warningEngine;
    private final QuantStrategyVersionMapper quantStrategyMapper;
    private final QuantModelMonitorMapper quantMonitorMapper;
    private final QuantBenchmarkProfileService benchmarkProfileService;

    public InvestmentAnalysisServiceImpl(InvestmentAssetService assetService,
                                         InvestmentProductMapper productMapper,
                                         ProductDailyQuoteMapper quoteMapper,
                                         InvestmentHorizonService horizonService,
                                         InvestmentHorizonProperties horizonProperties,
                                         InvestmentRuntimeProperties runtimeProperties,
                                         InvestmentAnalysisSnapshotMapper snapshotMapper,
                                         AnalysisServiceClient analysisClient,
                                         InvestmentDataQualityService dataQualityService,
                                         InvestmentSyncWorker syncWorker,
                                         WealthService wealthService,
                                         FinancialProfileMapper financialProfileMapper,
                                         InvestmentAiExplanationService aiExplanationService,
                                         ObjectMapper objectMapper,
                                         InvestmentDataJobService dataJobService,
                                         InvestmentFinancialWarningEngine warningEngine,
                                         QuantStrategyVersionMapper quantStrategyMapper,
                                         QuantModelMonitorMapper quantMonitorMapper,
                                         QuantBenchmarkProfileService benchmarkProfileService) {
        this.assetService = assetService;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.horizonService = horizonService;
        this.horizonProperties = horizonProperties;
        this.runtimeProperties = runtimeProperties;
        this.snapshotMapper = snapshotMapper;
        this.analysisClient = analysisClient;
        this.dataQualityService = dataQualityService;
        this.syncWorker = syncWorker;
        this.wealthService = wealthService;
        this.financialProfileMapper = financialProfileMapper;
        this.aiExplanationService = aiExplanationService;
        this.objectMapper = objectMapper;
        this.dataJobService = dataJobService;
        this.warningEngine = warningEngine;
        this.quantStrategyMapper = quantStrategyMapper;
        this.quantMonitorMapper = quantMonitorMapper;
        this.benchmarkProfileService = benchmarkProfileService;
    }

    @Override
    public InvestmentAssetDetailResponse detail(Long userId, Long assetId) {
        return autoAnalyze(userId, assetId);
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse updatePreference(Long userId, Long assetId,
                                                           HorizonProfileRequest request) {
        assetService.get(userId, assetId);
        horizonService.saveAssetOverride(userId, assetId, request);
        return autoAnalyze(userId, assetId);
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse clearPreference(Long userId, Long assetId) {
        assetService.get(userId, assetId);
        horizonService.clearAssetOverride(userId, assetId);
        return autoAnalyze(userId, assetId);
    }

    @Override
    public InvestmentAssetDetailResponse refresh(Long userId, Long assetId) {
        return build(userId, assetId, true, false);
    }

    @Override
    public InvestmentAssetDetailResponse retryData(Long userId, Long assetId) {
        return build(userId, assetId, false, true);
    }

    private InvestmentAssetDetailResponse autoAnalyze(Long userId, Long assetId) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        ResolvedHorizonProfile horizonProfile = horizonService.resolve(userId, assetId);
        List<ProductDailyQuote> quotes = loadQuotes(product);
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        Map<String, Object> latestQuality = dataQualityService.latestStatus(product);
        if (canReuseSnapshot(snapshot, quotes, latestQuality, horizonProfile, product)) {
            return withHistoryJob(userId, assetId, readOnlyDetail(userId, assetId));
        }
        return withHistoryJob(userId, assetId, build(userId, assetId, false, false));
    }

    private boolean canReuseSnapshot(
            InvestmentAnalysisSnapshot snapshot,
            List<ProductDailyQuote> quotes,
            Map<String, Object> latestQuality,
            ResolvedHorizonProfile horizonProfile,
            InvestmentProduct product) {
        if (snapshot == null || snapshot.getTechnicalJson() == null) {
            return false;
        }
        boolean blocked = latestQuality.get("datasetVersion") == null
                || "BLOCK".equalsIgnoreCase(text(latestQuality.get("decision")));
        if (blocked) {
            return false;
        }
        if (!"READY".equals(snapshot.getAnalysisStatus())
                || Boolean.TRUE.equals(snapshot.getHistoricalCache())) {
            return false;
        }
        String currentKey = analysisCacheKey(
                text(latestQuality.get("datasetVersion")),
                text(latestQuality.get("qualityRuleSetVersion")),
                horizonConfigJson(horizonProfile),
                runtimeProperties.getAnalysis().getStrategyVersion(),
                horizonProperties.getAnalysisRuleVersion(),
                product.getFundCategory(),
                product.getClassificationSource(),
                product.getClassificationVersion(),
                quoteHistoryMaterial(quotes),
                benchmarkCacheMaterial(product, quotes)
        );
        if (!Objects.equals(snapshot.getAnalysisCacheKey(), currentKey)) {
            return false;
        }
        LocalDate latestQuoteDate = quotes.isEmpty()
                ? null
                : quotes.get(quotes.size() - 1).getTradeDate();
        return latestQuoteDate == null
                || snapshot.getQuoteDate() == null
                || !snapshot.getQuoteDate().isBefore(latestQuoteDate);
    }

    private String analysisCacheKey(
            String datasetVersion,
            String qualityRuleSetVersion,
            String horizonConfigJson,
            String strategyVersion,
            String analysisRuleVersion,
            String fundCategory,
            String classificationSource,
            String classificationVersion,
            Map<String, Object> quoteHistoryMaterial,
            Map<String, Object> benchmarkMaterial) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("datasetVersion", datasetVersion);
        material.put("qualityRuleSetVersion", qualityRuleSetVersion);
        material.put("horizonConfig", horizonConfigJson);
        material.put("strategyVersion", strategyVersion);
        material.put("analysisRuleVersion", analysisRuleVersion);
        material.put("fundCategory", fundCategory);
        material.put("classificationSource", classificationSource);
        material.put("classificationVersion", classificationVersion);
        material.put("quoteHistory", quoteHistoryMaterial);
        material.put("benchmark", benchmarkMaterial);
        return hash(writeJson(material));
    }

    private String horizonConfigJson(ResolvedHorizonProfile horizonProfile) {
        return writeJson(horizonConfigMaterial(horizonProfile));
    }

    static Map<String, Object> horizonConfigMaterial(ResolvedHorizonProfile horizonProfile) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("version", horizonProfile.version());
        material.put("primaryHorizon", horizonProfile.primaryCode());
        Map<String, Map<String, Integer>> settings = new TreeMap<>();
        for (var setting : horizonProfile.settings()) {
            settings.put(setting.code(), Map.of(
                    "minDays", setting.minHoldingDays(),
                    "maxDays", setting.maxHoldingDays(),
                    "targetDays", setting.targetHoldingDays()
            ));
        }
        material.put("horizons", settings);
        return material;
    }

    private InvestmentAssetDetailResponse withHistoryJob(
            Long userId,
            Long assetId,
            InvestmentAssetDetailResponse response) {
        Map<String, Object> historyJob = dataJobService.statusForAsset(userId, assetId);
        int quoteCount = response.getQuoteSeries() == null
                ? 0
                : response.getQuoteSeries().size();
        if (historyJob.isEmpty()
                && quoteCount < horizonProperties.getMinimumHistoryTradingDays()) {
            InvestmentAssetView asset = response.getAsset();
            dataJobService.ensureQueued(
                    userId,
                    assetId,
                    asset.getProductId(),
                    asset.getProductType(),
                    false
            );
            historyJob = dataJobService.statusForAsset(userId, assetId);
        }
        response.getSourceStatus().put("historyJob", historyJob);
        return response;
    }

    @SuppressWarnings("unchecked")
    private InvestmentAssetDetailResponse readOnlyDetail(Long userId, Long assetId) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
        ResolvedHorizonProfile horizonProfile = horizonService.resolve(userId, assetId);
        List<ProductDailyQuote> quotes = loadQuotes(product);
        Map<String, Object> historyJob = dataJobService.statusForAsset(userId, assetId);
        if (historyJob.isEmpty() && quotes.size() < horizonProperties.getMinimumHistoryTradingDays()) {
            dataJobService.ensureQueued(userId, assetId, product.getId(), product.getProductType(), false);
            historyJob = dataJobService.statusForAsset(userId, assetId);
        }
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        Map<String, Object> quality = dataQualityService.latestStatus(product);
        boolean blocked = quality.get("datasetVersion") == null
                || "BLOCK".equals(String.valueOf(quality.get("decision")));
        boolean historicalCache = blocked && snapshot != null;
        Map<String, Object> technical = snapshot == null
                ? new LinkedHashMap<>(Map.of("status", "INSUFFICIENT", "verdict", "WAIT",
                "reason", "可靠分析正在后台准备中"))
                : readMap(snapshot.getTechnicalJson());
        Map<String, Object> fundamental = snapshot == null
                ? new LinkedHashMap<>(Map.of("status", "INSUFFICIENT", "verdict", "WAIT"))
                : readMap(snapshot.getFundamentalJson());
        Map<String, Object> fund = snapshot == null ? new LinkedHashMap<>() : readMap(snapshot.getFundJson());
        Map<String, Object> backtest = snapshot == null
                ? new LinkedHashMap<>(Map.of("status", "INSUFFICIENT", "occurrences", 0))
                : readMap(snapshot.getBacktestJson());
        WealthOverviewResponse wealth = wealthService.overview(userId);
        BigDecimal technicalScore = score(technical);
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        putFundClassification(sourceStatus, product);
        sourceStatus.put("dataState", historicalCache ? "STABLE_CACHE" : snapshot == null ? "PREPARING" : "READY");
        sourceStatus.put("quoteStatus", quotes.size() >= horizonProperties.getMinimumHistoryTradingDays()
                ? "READY" : "INSUFFICIENT");
        sourceStatus.put("adjustType", configuredAdjustType(product));
        sourceStatus.put("historicalCache", historicalCache);
        sourceStatus.put("quoteDate", quotes.isEmpty() ? null : quotes.get(quotes.size() - 1).getTradeDate());
        sourceStatus.put("analyzedAt", snapshot == null ? null : snapshot.getAnalyzedAt());
        sourceStatus.put("historyJob", historyJob);
        String datasetVersion = text(quality.get("datasetVersion"));
        String analysisVersion = snapshot == null
                ? text(technical.get("strategyVersion"))
                : snapshot.getStrategyVersion();
        LocalDateTime calculatedAt = snapshot == null
                ? LocalDateTime.now(runtimeZone())
                : snapshot.getAnalyzedAt();
        technical = withProvenance(
                technical, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        fundamental = withProvenance(
                fundamental, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        fund = withProvenance(
                fund, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        InvestmentAssetDetailResponse response = new InvestmentAssetDetailResponse();
        response.setAsset(asset);
        response.setTechnicalAnalysis(technical);
        response.setFundamentalAnalysis(fundamental);
        response.setPersonalizedAction(Map.of());
        Map<String, Object> warningSource = new LinkedHashMap<>(quality);
        if (blocked) {
            warningSource.put("decision", "BLOCK");
        }
        response.setFinancialWarnings(financialWarnings(
                userId, asset, wealth, technicalScore,
                financialProfileMapper.selectByUserId(userId),
                technical, backtest, warningSource, horizonProfile.primaryCode()));
        response.setDisclaimer(disclaimer(
                asset, horizonProfile.primaryCode(), text(quality.get("datasetVersion"))
        ));
        response.setBacktestSummary(backtest);
        response.setAiExplanation(adviceUnavailable(product.getProductType(), technical)
                ? Map.of()
                : aiExplanation(
                        snapshot, technical, fundamental, product,
                        horizonProfile.primaryCode()
                ));
        response.setSourceStatus(sourceStatus);
        response.setAnalysisPreference(horizonService.describe(horizonProfile));
        Object series = ("MUTUAL_FUND".equals(product.getProductType()) ? fund : technical).get("series");
        response.setQuoteSeries(displayQuoteSeries(quotes, series));
        return response;
    }

    private InvestmentAssetDetailResponse build(Long userId, Long assetId,
                                                boolean forceAnalysis,
                                                boolean refreshQuotes) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
        ResolvedHorizonProfile horizonProfile = horizonService.resolve(userId, assetId);
        Map<String, List<Integer>> horizons = horizonProfile.analysisRanges();
        int requiredHistoryDays = interactiveHistoryDays(horizonProfile, horizonProperties);
        LocalDate qualityEndDate = LocalDate.now(runtimeProperties.getMarket().getZone());
        LocalDate qualityStartDate = qualityEndDate.minusDays(
                calendarLookbackDays(requiredHistoryDays, horizonProperties));
        List<ProductDailyQuote> quotes = loadQuotes(product);
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        putFundClassification(sourceStatus, product);
        sourceStatus.put("quoteStatus", quotes.size() >= horizonProperties.getMinimumHistoryTradingDays()
                ? "READY" : "INSUFFICIENT");
        sourceStatus.put("quoteCount", quotes.size());
        sourceStatus.put("adjustType", configuredAdjustType(product));
        sourceStatus.put("requiredHistoryDays", requiredHistoryDays);
        sourceStatus.put("horizonProfileVersion", horizonProfile.version());
        sourceStatus.put("runtimeParameterVersion", runtimeProperties.getParameterVersion());
        sourceStatus.put("horizonWarnings", horizonProfile.warnings());
        sourceStatus.put("qualityConfigVersion", runtimeProperties.getDataQuality().getConfigVersion());

        InvestmentDataQualityService.Evaluation quality = null;
        try {
            quality = dataQualityService.resolve(
                    product, qualityStartDate, qualityEndDate, refreshQuotes);
            if (!quality.records().isEmpty()) {
                try {
                    syncWorker.persistDailyQuotes(product, quality.response());
                    quotes = loadQuotes(product);
                } catch (RuntimeException cacheException) {
                    sourceStatus.put("quoteCacheError", cacheException.getMessage());
                }
            }
            Map<String, Object> manifest = asMap(quality.response().get("manifest"));
            Map<String, Object> report = asMap(quality.response().get("qualityReport"));
            sourceStatus.put("datasetVersion", quality.datasetVersion());
            sourceStatus.put("qualityRuleSetVersion", quality.ruleSetVersion());
            sourceStatus.put("qualityStatus", quality.status());
            sourceStatus.put("qualityDecision", quality.snapshot().getDecision());
            sourceStatus.put("qualityIssues", report.getOrDefault("issues", List.of()));
            sourceStatus.put("qualityProviderWarnings",
                    quality.response().getOrDefault("warnings", List.of()));
            sourceStatus.put("dataProvider", manifest.get("provider"));
            sourceStatus.put("adapterVersion", manifest.get("adapterVersion"));
            sourceStatus.put("dataFetchedAt", manifest.get("fetchedAt"));
            sourceStatus.put("secondaryDatasetVersions", quality.secondaryDatasetVersions());
            sourceStatus.put("quoteCount", quotes.size());
            sourceStatus.put("qualityWindowRecordCount", quality.records().size());
            sourceStatus.put("quoteStatus", quotes.size()
                    >= horizonProperties.getMinimumHistoryTradingDays() ? "READY" : "INSUFFICIENT");
        } catch (RuntimeException exception) {
            sourceStatus.put("qualityStatus", "UNAVAILABLE");
            sourceStatus.put("qualityDecision", "WAITING");
            sourceStatus.put("dataQualityError", exception.getMessage());
        }
        boolean qualityBlocked = quality == null || quality.blocked();
        if (qualityBlocked) {
            sourceStatus.put("analysisGate", quality == null ? "WAITING" : "BLOCKED");
            try {
                dataJobService.ensureRecoveryQueued(
                        userId,
                        assetId,
                        product.getId(),
                        product.getProductType()
                );
            } catch (RuntimeException recoveryException) {
                sourceStatus.put("recoveryQueueError", recoveryException.getMessage());
            }
        } else {
            sourceStatus.put("analysisGate", "ALLOW");
        }

        if (quotes.isEmpty() && quality == null) {
            try {
                Map<String, Object> response = analysisClient.dailyQuotes(
                        product, qualityStartDate, qualityEndDate);
                syncWorker.persistDailyQuotes(product, response);
                quotes = loadQuotes(product);
            } catch (RuntimeException exception) {
                sourceStatus.put("displayQuoteError", exception.getMessage());
            }
        }

        String horizonConfigJson = horizonConfigJson(horizonProfile);
        String preferenceHash = hash(horizonConfigJson);
        LocalDate quoteDate = quality == null || quality.records().isEmpty()
                ? quotes.isEmpty() ? null : quotes.get(quotes.size() - 1).getTradeDate()
                : latestRecordDate(quality.records());
        Map<String, Object> fundBenchmark = qualityBlocked
                ? Map.of()
                : benchmarkPayload(product, quality.records());
        if (!fundBenchmark.isEmpty()) {
            sourceStatus.put("benchmarkStatus", fundBenchmark.get("status"));
            sourceStatus.put("benchmarkCode", fundBenchmark.get("code"));
            sourceStatus.put("benchmarkName", fundBenchmark.get("name"));
            sourceStatus.put("benchmarkSourceVersion", fundBenchmark.get("sourceVersion"));
            sourceStatus.put("benchmarkReason", fundBenchmark.get("reason"));
            if ("BENCHMARK_UNAVAILABLE".equals(fundBenchmark.get("status"))) {
                try {
                    dataJobService.ensureBenchmarkQueued(
                            userId, assetId, product.getId()
                    );
                } catch (RuntimeException queueException) {
                    sourceStatus.put("benchmarkQueueError", queueException.getMessage());
                }
            }
        }
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        String configuredStrategyVersion = runtimeProperties.getAnalysis().getStrategyVersion();
        String analysisCacheKey = qualityBlocked ? null : analysisCacheKey(
                quality.datasetVersion(),
                quality.ruleSetVersion(),
                horizonConfigJson,
                configuredStrategyVersion,
                horizonProperties.getAnalysisRuleVersion(),
                product.getFundCategory(),
                product.getClassificationSource(),
                product.getClassificationVersion(),
                quoteHistoryMaterial(quotes),
                benchmarkCacheMaterial(fundBenchmark)
        );
        boolean currentSnapshot = !qualityBlocked
                && isCurrentSnapshot(snapshot, analysisCacheKey);
        boolean compatibleSnapshot = currentSnapshot
                && isCompatibleSnapshot(snapshot, analysisCacheKey);
        boolean cacheHit = !forceAnalysis && compatibleSnapshot;
        Map<String, Object> technical;
        Map<String, Object> fundamental;
        Map<String, Object> backtest;
        Map<String, Object> fund;
        boolean historicalCacheUsed = false;
        if (qualityBlocked) {
            String gateStatus = quality == null ? "UNAVAILABLE" : "BLOCKED";
            String reason = quality == null
                    ? "数据质量服务暂不可用，正在等待重新校验"
                    : "数据完整性校验未通过，已停止生成分析结论";
            technical = Map.of("status", gateStatus, "reason", reason);
            fundamental = Map.of("status", gateStatus, "reason", reason);
            fund = "MUTUAL_FUND".equals(product.getProductType())
                    ? Map.of("status", gateStatus, "reason", reason)
                    : Map.of();
            backtest = Map.of("status", gateStatus, "reason", reason);
            sourceStatus.put("analysisStatus", gateStatus);
            sourceStatus.put("historicalCache", false);
        } else if (cacheHit) {
            technical = readMap(snapshot.getTechnicalJson());
            fundamental = readMap(snapshot.getFundamentalJson());
            fund = readMap(snapshot.getFundJson());
            backtest = readMap(snapshot.getBacktestJson());
            if (Boolean.TRUE.equals(snapshot.getHistoricalCache())
                    || !"READY".equals(snapshot.getAnalysisStatus())) {
                snapshot.setHistoricalCache(false);
                snapshot.setAnalysisStatus("READY");
                snapshotMapper.updateById(snapshot);
            }
        } else {
            List<Map<String, Object>> records = "MUTUAL_FUND".equals(product.getProductType())
                    ? quoteRecords(quotes)
                    : quality.records();
            try {
                if ("MUTUAL_FUND".equals(product.getProductType())) {
                    Map<String, Map<String, Integer>> fundHorizons = new LinkedHashMap<>();
                    for (var setting : horizonProfile.settings()) {
                        fundHorizons.put(setting.code(), Map.of(
                                "minDays", setting.minHoldingDays(),
                                "maxDays", setting.maxHoldingDays(),
                                "targetDays", setting.targetHoldingDays()
                        ));
                    }
                    fund = analysisClient.fundAnalysis(
                            records,
                            product.getFundCategory(),
                            fundHorizons,
                            horizonProfile.primaryCode(),
                            fundBenchmark
                    );
                    technical = fund;
                    fundamental = Map.of("status", "NOT_APPLICABLE", "verdict", "NOT_APPLICABLE");
                    backtest = Map.of(
                            "status", "NOT_APPLICABLE",
                            "reason", "基金分类专属策略尚未通过验证，未执行通用回测"
                    );
                } else {
                    technical = analysisClient.technicalAnalysis(
                            records, horizons, horizonProfile.primaryCode(), marketSnapshot(asset));
                    fundamental = analysisClient.fundamentalAnalysis(product.getCode(), product.getMarket());
                    fund = Map.of();
                    backtest = analysisClient.backtest(records, horizons);
                }
                requireStrategyVersion(configuredStrategyVersion, technical, "分析");
                if (!"MUTUAL_FUND".equals(product.getProductType())) {
                    requireStrategyVersion(configuredStrategyVersion, backtest, "回测");
                }
                sourceStatus.put("analysisStrategyVersion", technical.get("strategyVersion"));
                if (backtest.get("strategyVersion") != null) {
                    sourceStatus.put("backtestStrategyVersion", backtest.get("strategyVersion"));
                }
                sourceStatus.put("analysisStatus", analysisResultStatus(technical));
                dataQualityService.claim(quality);
                snapshot = saveSnapshot(userId, assetId, snapshot, quoteDate, preferenceHash,
                        horizonProfile.version(), horizonConfigJson,
                        quality.datasetVersion(), quality.ruleSetVersion(), configuredStrategyVersion,
                        analysisCacheKey, quality.status(),
                        technical, fundamental, fund, backtest, sourceStatus);
                currentSnapshot = true;
                compatibleSnapshot = isCompatibleSnapshot(snapshot, analysisCacheKey);
            } catch (RuntimeException exception) {
                if (!compatibleSnapshot) {
                    boolean fundProduct = "MUTUAL_FUND".equals(product.getProductType());
                    technical = fundProduct
                            ? Map.of(
                                    "status", "FAILED",
                                    "verdict", "WAIT",
                                    "action", "WAIT",
                                    "adviceStatus", "UNAVAILABLE",
                                    "reasonCode", "ANALYSIS_SERVICE_UNAVAILABLE",
                                    "reason", "基金分析服务暂不可用，已停止展示操作建议")
                            : Map.of("status", "FAILED", "verdict", "WAIT",
                                    "message", "技术分析服务暂不可用");
                    fundamental = Map.of("status", "INSUFFICIENT", "verdict", "INSUFFICIENT");
                    fund = fundProduct ? technical : Map.of();
                    backtest = Map.of("status", "FAILED", "occurrences", 0);
                } else {
                    technical = readMap(snapshot.getTechnicalJson());
                    fundamental = readMap(snapshot.getFundamentalJson());
                    fund = readMap(snapshot.getFundJson());
                    backtest = readMap(snapshot.getBacktestJson());
                    historicalCacheUsed = true;
                }
                sourceStatus.put("analysisStatus", historicalCacheUsed ? "CACHED" : "FAILED");
                sourceStatus.put("analysisError", exception.getMessage());
                sourceStatus.put("historicalCache", historicalCacheUsed);
            }
        }

        WealthOverviewResponse wealth = wealthService.overview(userId);
        asset = assetService.get(userId, assetId);
        BigDecimal score = score(technical);
        if (!qualityBlocked && !historicalCacheUsed && compatibleSnapshot
                && snapshot != null && snapshot.getId() != null
                && !adviceUnavailable(product.getProductType(), technical)) {
            Map<String, Object> technicalSignal = new LinkedHashMap<>();
            technicalSignal.put("score", technical.get("score"));
            technicalSignal.put("verdict", technical.getOrDefault(
                    "verdict", technical.getOrDefault("action", "INSUFFICIENT")));
            technicalSignal.put("zones", technical.getOrDefault("actionZones", Map.of()));
            String combinedSignalHash = hash(writeJson(Map.of("technical", technicalSignal)));
            boolean signalChanged = invalidateStaleExplanation(snapshot, combinedSignalHash);
            boolean explanationNeedsRefresh = signalChanged
                    || snapshot.getAiExplanation() == null || snapshot.getAiExplanation().isBlank();
            if (signalChanged) {
                snapshotMapper.updateById(snapshot);
            }
            if (explanationNeedsRefresh) {
                aiExplanationService.refreshIfAllowed(snapshot.getId(), combinedSignalHash, product.getName(),
                        writeJson(technical), writeJson(fundamental));
            }
        }
        List<Map<String, Object>> warnings = financialWarnings(
                userId, asset, wealth, score,
                financialProfileMapper.selectByUserId(userId),
                technical, backtest, sourceStatus, horizonProfile.primaryCode()
        );
        sourceStatus.putIfAbsent("analysisStatus", "READY");
        sourceStatus.put("cacheHit", cacheHit);
        sourceStatus.putIfAbsent("historicalCache", false);
        sourceStatus.put("ruleVersion", horizonProperties.getAnalysisRuleVersion());
        sourceStatus.put("strategyVersion", configuredStrategyVersion);
        sourceStatus.put("quoteDate", quoteDate);
        sourceStatus.put("analyzedAt", currentSnapshot ? snapshot.getAnalyzedAt() : null);
        boolean reliableCache = Boolean.TRUE.equals(sourceStatus.get("historicalCache"));
        sourceStatus.put("dataState", quality == null
                ? "WAITING"
                : quality.blocked()
                ? "BLOCKED"
                : reliableCache
                ? "STABLE_CACHE"
                : "FAILED".equals(sourceStatus.get("analysisStatus"))
                ? "WAITING"
                : !"READY".equals(text(sourceStatus.get("analysisStatus")))
                ? text(sourceStatus.get("analysisStatus"))
                : "READY");
        String datasetVersion = text(sourceStatus.get("datasetVersion"));
        String analysisVersion = !currentSnapshot || qualityBlocked
                ? text(technical.get("strategyVersion"))
                : snapshot.getStrategyVersion();
        LocalDateTime calculatedAt = !currentSnapshot || qualityBlocked
                ? LocalDateTime.now(runtimeZone())
                : snapshot.getAnalyzedAt();
        technical = withProvenance(
                technical, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        fundamental = withProvenance(
                fundamental, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        fund = withProvenance(
                fund, asset, horizonProfile.primaryCode(),
                datasetVersion, analysisVersion, calculatedAt,
                "DETERMINISTIC_ANALYSIS"
        );
        InvestmentAssetDetailResponse response = new InvestmentAssetDetailResponse();
        response.setAsset(asset);
        response.setTechnicalAnalysis(technical);
        response.setFundamentalAnalysis(fundamental);
        response.setPersonalizedAction(Map.of());
        response.setFinancialWarnings(warnings);
        response.setDisclaimer(disclaimer(
                asset,
                horizonProfile.primaryCode(),
                text(sourceStatus.get("datasetVersion"))
        ));
        response.setBacktestSummary(backtest);
        response.setAiExplanation(qualityBlocked
                || !compatibleSnapshot
                || adviceUnavailable(product.getProductType(), technical)
                ? Map.of()
                : aiExplanation(
                        snapshot, technical, fundamental, product,
                        horizonProfile.primaryCode()
                ));
        response.setSourceStatus(userSafeSourceStatus(sourceStatus));
        response.setAnalysisPreference(horizonService.describe(horizonProfile));
        Object series = ("MUTUAL_FUND".equals(product.getProductType()) ? fund : technical).get("series");
        response.setQuoteSeries(displayQuoteSeries(quotes, series));
        return response;
    }

    private static Map<String, Object> userSafeSourceStatus(Map<String, Object> sourceStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "dataState", "quoteStatus", "adjustType", "historicalCache", "quoteDate", "analyzedAt",
                "qualityStatus", "qualityDecision", "analysisGate", "qualityIssues", "dataQualityError",
                "analysisStatus", "fundTypeRaw", "fundCategory", "classificationSource",
                "classificationVersion")) {
            if (sourceStatus.containsKey(key)) result.put(key, sourceStatus.get(key));
        }
        return result;
    }

    private static void putFundClassification(
            Map<String, Object> sourceStatus,
            InvestmentProduct product) {
        if (!"MUTUAL_FUND".equals(product.getProductType())) return;
        sourceStatus.put("fundTypeRaw", product.getFundTypeRaw());
        sourceStatus.put("fundCategory", product.getFundCategory());
        sourceStatus.put("classificationSource", product.getClassificationSource());
        sourceStatus.put("classificationVersion", product.getClassificationVersion());
    }

    private List<ProductDailyQuote> loadQuotes(InvestmentProduct product) {
        List<ProductDailyQuote> all = quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .orderByAsc(ProductDailyQuote::getTradeDate));
        Map<LocalDate, ProductDailyQuote> byDate = new TreeMap<>();
        String requiredAdjustType = configuredAdjustType(product);
        for (ProductDailyQuote quote : all) {
            ProductDailyQuote current = byDate.get(quote.getTradeDate());
            if (requiredAdjustType.equals(quote.getAdjustType()) || current == null) {
                byDate.put(quote.getTradeDate(), quote);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private InvestmentAnalysisSnapshot findSnapshot(Long userId, Long assetId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<InvestmentAnalysisSnapshot>()
                .eq(InvestmentAnalysisSnapshot::getUserId, userId)
                .eq(InvestmentAnalysisSnapshot::getAssetId, assetId)
                .eq(InvestmentAnalysisSnapshot::getRuleVersion,
                        horizonProperties.getAnalysisRuleVersion()).last("LIMIT 1"));
    }

    private InvestmentAnalysisSnapshot saveSnapshot(Long userId, Long assetId,
                                                      InvestmentAnalysisSnapshot snapshot,
                                                      LocalDate quoteDate, String preferenceHash,
                                                      String horizonProfileVersion,
                                                      String horizonConfigJson,
                                                      String datasetVersion,
                                                      String qualityRuleSetVersion,
                                                      String strategyVersion,
                                                      String analysisCacheKey,
                                                      String qualityStatus,
                                                      Map<String, Object> technical,
                                                      Map<String, Object> fundamental,
                                                      Map<String, Object> fund,
                                                      Map<String, Object> backtest,
                                                      Map<String, Object> sourceStatus) {
        if (snapshot == null) {
            snapshot = new InvestmentAnalysisSnapshot();
            snapshot.setUserId(userId);
            snapshot.setAssetId(assetId);
            snapshot.setRuleVersion(horizonProperties.getAnalysisRuleVersion());
        }
        snapshot.setPreferenceHash(preferenceHash);
        snapshot.setHorizonProfileVersion(horizonProfileVersion);
        snapshot.setHorizonConfigJson(horizonConfigJson);
        snapshot.setDatasetVersion(datasetVersion);
        snapshot.setQualityRuleSetVersion(qualityRuleSetVersion);
        snapshot.setStrategyVersion(strategyVersion);
        snapshot.setAnalysisCacheKey(analysisCacheKey);
        snapshot.setQualityStatus(qualityStatus);
        snapshot.setHistoricalCache(false);
        snapshot.setQuoteDate(quoteDate);
        snapshot.setTechnicalJson(writeJson(technical));
        snapshot.setFundamentalJson(writeJson(fundamental));
        snapshot.setFundJson(writeJson(fund));
        snapshot.setBacktestJson(writeJson(backtest));
        snapshot.setSourceStatusJson(writeJson(sourceStatus));
        snapshot.setAnalysisStatus(analysisResultStatus(technical));
        if (adviceUnavailable("MUTUAL_FUND", technical)) {
            snapshot.setAiExplanation(null);
            snapshot.setSignalHash(null);
            snapshot.setAiUpdatedAt(null);
        }
        snapshot.setAnalyzedAt(LocalDateTime.now());
        if (snapshot.getId() == null) snapshotMapper.insert(snapshot); else snapshotMapper.updateById(snapshot);
        return snapshot;
    }

    private List<Map<String, Object>> quoteRecords(List<ProductDailyQuote> quotes) {
        return quotes.stream().map(InvestmentAnalysisServiceImpl::quoteRecord).toList();
    }

    private static Map<String, Object> quoteHistoryMaterial(List<ProductDailyQuote> quotes) {
        if (quotes.isEmpty()) return Map.of("count", 0);
        return Map.of(
                "count", quotes.size(),
                "startDate", quotes.get(0).getTradeDate().toString(),
                "endDate", quotes.get(quotes.size() - 1).getTradeDate().toString()
        );
    }

    static List<Map<String, Object>> displayQuoteSeries(
            List<ProductDailyQuote> quotes,
            Object analysisSeries) {
        Map<String, Map<String, Object>> indicatorsByDate = new HashMap<>();
        if (analysisSeries instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                raw.forEach((key, value) -> row.put(String.valueOf(key), value));
                String date = text(row.get("date"));
                if (date == null) {
                    date = text(row.get("data_date"));
                }
                if (date == null) {
                    date = text(row.get("dataDate"));
                }
                if (date != null) {
                    indicatorsByDate.put(date, row);
                }
            }
        }
        return quotes.stream().map(quote -> {
            Map<String, Object> merged = quoteRecord(quote);
            Map<String, Object> indicators = indicatorsByDate.get(quote.getTradeDate().toString());
            if (indicators != null) {
                merged.putAll(indicators);
            }
            return merged;
        }).toList();
    }

    static String analysisResultStatus(Map<String, Object> result) {
        String status = text(result == null ? null : result.get("status"));
        if (status == null) return "FAILED";
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "READY", "INSUFFICIENT", "BLOCKED", "UNAVAILABLE", "FAILED" ->
                    status.toUpperCase(Locale.ROOT);
            default -> "FAILED";
        };
    }

    static boolean isCompatibleSnapshot(
            InvestmentAnalysisSnapshot snapshot,
            String analysisCacheKey) {
        return isCurrentSnapshot(snapshot, analysisCacheKey)
                && "READY".equals(snapshot.getAnalysisStatus());
    }

    private static boolean isCurrentSnapshot(
            InvestmentAnalysisSnapshot snapshot,
            String analysisCacheKey) {
        return snapshot != null
                && Objects.equals(snapshot.getAnalysisCacheKey(), analysisCacheKey);
    }

    static boolean adviceUnavailable(String productType, Map<String, ?> result) {
        return "MUTUAL_FUND".equals(productType)
                && result != null
                && "UNAVAILABLE".equals(String.valueOf(result.get("adviceStatus")));
    }

    private static Map<String, Object> quoteRecord(ProductDailyQuote quote) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data_date", quote.getTradeDate().toString());
        item.put("open", quote.getOpenPrice());
        item.put("high", quote.getHighPrice());
        item.put("low", quote.getLowPrice());
        item.put("close", quote.getClosePrice());
        item.put("volume", quote.getVolume());
        return item;
    }

    private static Map<String, Object> marketSnapshot(InvestmentAssetView asset) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (asset.getTurnoverRate() != null) snapshot.put("turnoverRate", asset.getTurnoverRate());
        if (asset.getVolumeRatio() != null) snapshot.put("volumeRatio", asset.getVolumeRatio());
        if (asset.getAmplitude() != null) snapshot.put("amplitude", asset.getAmplitude());
        return snapshot;
    }

    private String configuredAdjustType(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType())
                ? runtimeProperties.getDataQuality().getFundAdjustType()
                : runtimeProperties.getDataQuality().getStockAdjustType();
    }

    private static LocalDate latestRecordDate(List<Map<String, Object>> records) {
        return records.stream()
                .map(record -> LocalDate.parse(String.valueOf(record.get("data_date"))))
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    private static LocalDate earliestRecordDate(List<Map<String, Object>> records) {
        return records.stream()
                .map(record -> LocalDate.parse(String.valueOf(record.get("data_date"))))
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    private Map<String, Object> benchmarkPayload(
            InvestmentProduct product,
            List<Map<String, Object>> records) {
        if (!FundClassificationPolicy.indexBased(product.getFundCategory()) || records.isEmpty()) {
            return Map.of();
        }
        LocalDate startDate = earliestRecordDate(records);
        LocalDate endDate = latestRecordDate(records);
        return withBenchmarkName(
                product,
                endDate,
                benchmarkPayload(resolveFundBenchmark(product, startDate, endDate))
        );
    }

    private Map<String, Object> benchmarkCacheMaterial(
            InvestmentProduct product,
            List<ProductDailyQuote> quotes) {
        if (!FundClassificationPolicy.indexBased(product.getFundCategory()) || quotes.isEmpty()) {
            return Map.of();
        }
        LocalDate startDate = quotes.get(0).getTradeDate();
        LocalDate endDate = quotes.get(quotes.size() - 1).getTradeDate();
        return benchmarkCacheMaterial(withBenchmarkName(
                product,
                endDate,
                benchmarkPayload(resolveFundBenchmark(product, startDate, endDate))
        ));
    }

    private Map<String, Object> withBenchmarkName(
            InvestmentProduct product,
            LocalDate asOfDate,
            Map<String, Object> benchmark) {
        BenchmarkProfile profile = benchmarkProfileService.configuration(
                product.getProductType(), product.getCode(), asOfDate
        );
        if (profile != null && profile.getDisplayName() != null
                && !profile.getDisplayName().isBlank()) {
            benchmark.put("name", profile.getDisplayName());
        }
        return benchmark;
    }

    private QuantBenchmarkProfileService.ResolvedBenchmark resolveFundBenchmark(
            InvestmentProduct product,
            LocalDate startDate,
            LocalDate endDate) {
        BenchmarkProfile profile = benchmarkProfileService.configuration(
                product.getProductType(), product.getCode(), endDate
        );
        if (profile == null || !Objects.equals(product.getCode(), profile.getProductCode())) {
            try {
                AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                        product.getProductType(), product.getCode()
                );
                benchmarkProfileService.configureImportedFundBenchmark(product, resolved);
                profile = benchmarkProfileService.configuration(
                        product.getProductType(), product.getCode(), endDate
                );
            } catch (RuntimeException exception) {
                return new QuantBenchmarkProfileService.ResolvedBenchmark(
                        false,
                        null,
                        product.getFundCategory(),
                        null,
                        List.of(),
                        "BENCHMARK_UNAVAILABLE",
                        "存量基金基准补齐失败：" + exception.getMessage()
                );
            }
        }
        if (profile == null || !Objects.equals(product.getCode(), profile.getProductCode())) {
            return new QuantBenchmarkProfileService.ResolvedBenchmark(
                    false,
                    null,
                    product.getFundCategory(),
                    null,
                    List.of(),
                    "BENCHMARK_UNAVAILABLE",
                    "未配置与当前基金代码精确匹配的官方基准"
            );
        }
        return benchmarkProfileService.resolveCached(
                product.getProductType(), product.getCode(), endDate, startDate, endDate
        );
    }

    static Map<String, Object> benchmarkPayload(
            QuantBenchmarkProfileService.ResolvedBenchmark benchmark) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", benchmark.available() ? "READY" : benchmark.failureCode());
        if (benchmark.benchmarkCode() != null) {
            payload.put("code", benchmark.benchmarkCode());
        }
        if (benchmark.sourceVersion() != null) {
            payload.put("sourceVersion", benchmark.sourceVersion());
        }
        if (benchmark.failureSummary() != null) {
            payload.put("reason", benchmark.failureSummary());
        }
        payload.put("records", benchmark.records());
        return payload;
    }

    private static Map<String, Object> benchmarkCacheMaterial(
            Map<String, Object> benchmark) {
        if (benchmark.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> material = new LinkedHashMap<>();
        for (String key : List.of("status", "code", "name", "sourceVersion")) {
            if (benchmark.get(key) != null) {
                material.put(key, benchmark.get(key));
            }
        }
        return material;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void requireStrategyVersion(String expected,
                                               Map<String, Object> response,
                                               String operation) {
        String actual = String.valueOf(response.get("strategyVersion"));
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(operation + "策略版本不一致，期望 "
                    + expected + "，实际 " + actual);
        }
    }

    static BigDecimal score(Map<String, Object> technical) {
        Object value = technical.get("score");
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private List<Map<String, Object>> financialWarnings(
            Long userId,
            InvestmentAssetView asset,
            WealthOverviewResponse wealth,
            BigDecimal technicalScore,
            FinancialProfile profile,
            Map<String, Object> technical,
            Map<String, Object> backtest,
            Map<String, Object> source,
            String horizonCode
    ) {
        QuantRiskContext model = quantRiskContext(
                userId,
                asset.getId(),
                horizonCode
        );
        Map<String, Object> fullHistory = asMap(technical.get("fullHistory"));
        return warningEngine.evaluate(new InvestmentFinancialWarningEngine.Input(
                asset.getId(),
                asset.getName(),
                asset.getMarketValueCny(),
                asset.getTurnoverRate(),
                wealth,
                profile,
                technicalScore,
                decimalNumber(fullHistory.get("annualizedVolatility")),
                firstNumber(
                        fullHistory.get("maxDrawdown"),
                        technical.get("maxDrawdown"),
                        backtest.get("maxDrawdown")
                ),
                firstText(
                        source.get("qualityDecision"),
                        source.get("decision")
                ),
                model.driftStatus(),
                horizonCode,
                text(source.get("datasetVersion")),
                model.modelVersion()
        ));
    }

    private Map<String, Object> aiExplanation(InvestmentAnalysisSnapshot snapshot,
                                              Map<String, Object> technical,
                                              Map<String, Object> fundamental,
                                              InvestmentProduct product,
                                              String horizonCode) {
        if (snapshot != null && snapshot.getAiExplanation() != null && !snapshot.getAiExplanation().isBlank()) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> stored = readMap(snapshot.getAiExplanation());
            boolean structured = stored.get("summary") instanceof String;
            String summary = structured
                    ? text(stored.get("summary"))
                    : limitText(snapshot.getAiExplanation(), 80);
            result.put("status", "READY");
            result.put("summary", summary);
            result.put("reasons", structured
                    ? listOfText(stored.get("reasons"))
                    : List.of());
            result.put("risks", structured
                    ? listOfText(stored.get("risks"))
                    : List.of());
            result.put("technicalDetails", structured
                    ? text(stored.get("technicalDetails"))
                    : snapshot.getAiExplanation());
            result.put("text", summary);
            result.put("updatedAt", snapshot.getAiUpdatedAt());
            result.put("cooldownMinutes", runtimeProperties.getAi().getCooldownMinutes());
            result.put("sourceType", structured ? "AI_STRUCTURED" : "AI_LEGACY");
            result.put("provenance", explanationProvenance(
                    product, horizonCode, snapshot
            ));
            return result;
        }
        String verdict = String.valueOf(technical.getOrDefault("verdict", technical.getOrDefault("action", "WAIT")));
        String summary = product.getName() + "当前结论为“" + verdict + "”";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PENDING");
        result.put("summary", summary);
        result.put("reasons", List.of("系统正在根据已保存的分析结果生成简短说明"));
        result.put("risks", List.of());
        result.put("technicalDetails", "");
        result.put("text", summary);
        result.put("cooldownMinutes", runtimeProperties.getAi().getCooldownMinutes());
        result.put("isRuleFallback", true);
        result.put("sourceType", "RULE_FALLBACK");
        result.put("provenance", explanationProvenance(
                product, horizonCode, snapshot
        ));
        return result;
    }

    private QuantRiskContext quantRiskContext(
            Long userId,
            Long assetId,
            String horizonCode
    ) {
        QuantStrategyVersion strategy = quantStrategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, userId)
                        .eq(QuantStrategyVersion::getAssetId, assetId)
                        .eq(QuantStrategyVersion::getHorizonCode, horizonCode)
                        .orderByDesc(QuantStrategyVersion::getActivatedAt)
                        .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT 1")
        );
        if (strategy == null || strategy.getModelVersion() == null) {
            return new QuantRiskContext(null, "NOT_MONITORED");
        }
        QuantModelMonitor monitor = quantMonitorMapper.selectOne(
                new LambdaQueryWrapper<QuantModelMonitor>()
                        .eq(
                                QuantModelMonitor::getModelVersion,
                                strategy.getModelVersion()
                        )
                        .orderByDesc(QuantModelMonitor::getMonitoredOn)
                        .orderByDesc(QuantModelMonitor::getCreatedAt)
                        .last("LIMIT 1")
        );
        return new QuantRiskContext(
                strategy.getModelVersion(),
                monitor == null ? "NOT_MONITORED" : monitor.getDriftStatus()
        );
    }

    private Map<String, Object> disclaimer(
            InvestmentAssetView asset,
            String horizonCode,
            String datasetVersion
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", "结果仅用于辅助分析，不连接券商，也不会自动交易");
        result.put("sourceType", "LEGAL_NOTICE");
        result.put("assetId", asset.getId());
        result.put("assetName", asset.getName());
        result.put("horizonCode", horizonCode);
        result.put("datasetVersion", datasetVersion);
        result.put(
                "calculatedAt",
                LocalDateTime.now(runtimeZone())
        );
        return result;
    }

    private static Map<String, Object> withProvenance(
            Map<String, Object> source,
            InvestmentAssetView asset,
            String horizonCode,
            String datasetVersion,
            String modelVersion,
            LocalDateTime calculatedAt,
            String sourceType
    ) {
        Map<String, Object> result = new LinkedHashMap<>(
                source == null ? Map.of() : source
        );
        result.put("provenance", provenance(
                asset, horizonCode, datasetVersion, modelVersion,
                calculatedAt, sourceType
        ));
        return result;
    }

    private static Map<String, Object> provenance(
            InvestmentAssetView asset,
            String horizonCode,
            String datasetVersion,
            String modelVersion,
            LocalDateTime calculatedAt,
            String sourceType
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetId", asset.getId());
        result.put("assetName", asset.getName());
        result.put("assetCode", asset.getCode());
        result.put("horizonCode", horizonCode);
        result.put("datasetVersion", datasetVersion);
        result.put("modelVersion", modelVersion);
        result.put("calculatedAt", calculatedAt);
        result.put("sourceType", sourceType);
        return result;
    }

    private Map<String, Object> explanationProvenance(
            InvestmentProduct product,
            String horizonCode,
            InvestmentAnalysisSnapshot snapshot
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assetName", product.getName());
        result.put("assetCode", product.getCode());
        result.put("horizonCode", horizonCode);
        result.put(
                "datasetVersion",
                snapshot == null ? null : snapshot.getDatasetVersion()
        );
        result.put(
                "modelVersion",
                snapshot == null ? null : snapshot.getStrategyVersion()
        );
        result.put(
                "calculatedAt",
                snapshot == null ? LocalDateTime.now(
                        runtimeZone()
                ) : snapshot.getAiUpdatedAt()
        );
        return result;
    }

    private ZoneId runtimeZone() {
        ZoneId zone = runtimeProperties.getMarket().getZone();
        return zone == null ? ZoneId.systemDefault() : zone;
    }

    private static List<String> listOfText(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .limit(3)
                .toList();
    }

    private static String limitText(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maximumCharacters
                ? trimmed
                : trimmed.substring(0, maximumCharacters);
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String result = text(value);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isBlank() || "null".equalsIgnoreCase(result)
                ? null
                : result;
    }

    private static Double firstNumber(Object... values) {
        for (Object value : values) {
            Double result = decimalNumber(value);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static Double decimalNumber(Object value) {
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

    private record QuantRiskContext(
            String modelVersion,
            String driftStatus
    ) {
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("分析缓存序列化失败", exception);
        }
    }

    private static String hash(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean invalidateStaleExplanation(InvestmentAnalysisSnapshot snapshot,
                                              String combinedSignalHash) {
        if (Objects.equals(snapshot.getSignalHash(), combinedSignalHash)) {
            return false;
        }
        snapshot.setSignalHash(combinedSignalHash);
        snapshot.setAiExplanation(null);
        return true;
    }

    static int requiredHistoryDays(ResolvedHorizonProfile profile,
                                   InvestmentHorizonProperties properties) {
        long indicatorHistory = Math.max(
                properties.getMinimumHistoryTradingDays(),
                (long) profile.requiredHistoryDays() * properties.getHistoryMultiplier());
        return (int) Math.min(properties.getMaxHistoryTradingDays(), indicatorHistory);
    }

    static int interactiveHistoryDays(ResolvedHorizonProfile profile,
                                      InvestmentHorizonProperties properties) {
        long horizonHistory = (long) profile.requiredHistoryDays()
                * properties.getInteractiveHistoryMultiplier() + 1;
        long required = Math.max(
                Math.max(properties.getMinimumHistoryTradingDays(),
                        properties.getIndicatorWarmupTradingDays()),
                horizonHistory);
        return (int) Math.min(properties.getMaxHistoryTradingDays(), required);
    }

    static int calendarLookbackDays(int tradingDays,
                                    InvestmentHorizonProperties properties) {
        if (tradingDays < 1) {
            throw new IllegalArgumentException("交易日数量必须为正整数");
        }
        long calendarDays = (long) Math.ceil(
                tradingDays * (double) properties.getCalendarDaysPerYear()
                        / properties.getTradingDaysPerYear())
                + properties.getCalendarBufferDays();
        return (int) Math.min(Integer.MAX_VALUE, calendarDays);
    }

    static boolean shouldRefreshQuotes(int quoteCount, int requiredHistoryDays, boolean refreshQuotes) {
        return quoteCount < requiredHistoryDays || refreshQuotes;
    }

}
