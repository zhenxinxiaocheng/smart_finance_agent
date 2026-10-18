package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.domain.FundClassificationPolicy;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
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
    private final WealthService wealthService;
    private final FinancialProfileMapper financialProfileMapper;
    private final ObjectMapper objectMapper;
    private final ObjectWriter fingerprintWriter;
    private final InvestmentDataJobService dataJobService;
    private final InvestmentFinancialWarningEngine warningEngine;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final InvestmentDetailCacheService detailCache;
    private final InvestmentAssetMapper assetMapper;

    public InvestmentAnalysisServiceImpl(InvestmentAssetService assetService,
                                         InvestmentProductMapper productMapper,
                                         ProductDailyQuoteMapper quoteMapper,
                                         InvestmentHorizonService horizonService,
                                         InvestmentHorizonProperties horizonProperties,
                                         InvestmentRuntimeProperties runtimeProperties,
                                         InvestmentAnalysisSnapshotMapper snapshotMapper,
                                         AnalysisServiceClient analysisClient,
                                         InvestmentDataQualityService dataQualityService,
                                         WealthService wealthService,
                                         FinancialProfileMapper financialProfileMapper,
                                         ObjectMapper objectMapper,
                                         InvestmentDataJobService dataJobService,
                                         InvestmentFinancialWarningEngine warningEngine,
                                         QuantBenchmarkProfileService benchmarkProfileService,
                                         InvestmentDetailCacheService detailCache,
                                         InvestmentAssetMapper assetMapper) {
        this.assetService = assetService;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.horizonService = horizonService;
        this.horizonProperties = horizonProperties;
        this.runtimeProperties = runtimeProperties;
        this.snapshotMapper = snapshotMapper;
        this.analysisClient = analysisClient;
        this.dataQualityService = dataQualityService;
        this.wealthService = wealthService;
        this.financialProfileMapper = financialProfileMapper;
        this.objectMapper = objectMapper;
        this.fingerprintWriter = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.dataJobService = dataJobService;
        this.warningEngine = warningEngine;
        this.benchmarkProfileService = benchmarkProfileService;
        this.detailCache = detailCache;
        this.assetMapper = assetMapper;
    }

    @Override
    public InvestmentAssetDetailResponse detail(Long userId, Long assetId) {
        // Always authorize and check live preferences, quality and snapshot metadata.
        DetailContext context = detailContext(userId, assetId);
        InvestmentDetailCacheService.Entry cached = detailCache.get(userId, assetId);
        // Reuse history validation only within the fresh TTL; reads never extend that window.
        if (cached != null && cached.fresh(Instant.now())
                && Objects.equals(cached.contextKey(), context.key())) {
            log.debug("Investment detail cache HIT");
            return detailStatus(cached.data(), "FRESH", context.job());
        }
        log.debug("Investment detail cache MISS");
        InvestmentAssetDetailResponse response = readOnlyDetail(userId, assetId, context);
        detailCache.put(userId, assetId, context.key(), response, context.checkedAt());
        return detailStatus(response, "MISS", context.job());
    }

    @Override
    public boolean needsRecovery(Long userId, Long assetId) {
        DetailContext context = detailContext(userId, assetId);
        AnalysisHistory history = detailHistory(context, loadQuotes(context.product()));
        AnalysisEligibility eligibility = analysisEligibility(context.product(), history,
                findSnapshot(userId, assetId), context.horizon(), context.quality(), null);
        return history.records().size() >= horizonProperties.getMinimumHistoryTradingDays()
                && !eligibility.blocked() && !eligibility.reusableSnapshot();
    }

    private record DetailContext(InvestmentAssetView asset, InvestmentProduct product,
                                 ResolvedHorizonProfile horizon, Map<String, Object> quality,
                                 InvestmentAnalysisSnapshot snapshot, InvestmentDataJob job,
                                 String holdingsRevision, String key, Instant checkedAt) {}

    private String holdingsRevision(Long userId) {
        // Also reject fills racing with another asset's eviction, including legacy holdings.
        return hash(writeJson(assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                .select(InvestmentAsset::getId, InvestmentAsset::getQuantity,
                        InvestmentAsset::getAverageCost, InvestmentAsset::getUpdatedAt)
                .eq(InvestmentAsset::getUserId, userId).orderByAsc(InvestmentAsset::getId))));
    }

    private DetailContext detailContext(Long userId, Long assetId) {
        Instant checkedAt = Instant.now();
        InvestmentAssetView asset = assetService.get(userId, assetId);
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
        ResolvedHorizonProfile horizon = horizonService.resolve(userId, assetId);
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId, true);
        Map<String, Object> quality = dataQualityService.analysisStatus(product,
                snapshot == null ? null : snapshot.getDatasetVersion());
        String holdingsRevision = holdingsRevision(userId);
        // Includes holdings, global/asset preferences and the latest persisted quality gate.
        // A failed Redis eviction must not expose a previous owner's data or a blocked conclusion.
        String key = hash(writeFingerprintJson(Arrays.asList(asset, horizonConfigMaterial(horizon), quality, holdingsRevision,
                runtimeProperties.getAnalysis().getStrategyVersion(), horizonProperties.getAnalysisRuleVersion(),
                snapshot == null ? null : Arrays.asList(snapshot.getAnalysisCacheKey(), snapshot.getAnalyzedAt(),
                        snapshot.getAnalysisStatus(), snapshot.getUpdatedAt()))));
        return new DetailContext(asset, product, horizon, quality, snapshot,
                dataJobService.analysisJob(userId, assetId, product.getProductType()), holdingsRevision, key, checkedAt);
    }

    private AnalysisHistory detailHistory(DetailContext context, List<ProductDailyQuote> quotes) {
        return analysisHistory(context.product(), quotes,
                interactiveHistoryDays(context.horizon(), horizonProperties), context.quality());
    }

    private InvestmentAssetDetailResponse detailStatus(
            InvestmentAssetDetailResponse response, String cacheStatus, InvestmentDataJob job) {
        // 任务状态每次读取；不随 Redis 响应缓存冻结，也不因状态变化触发分析。
        Map<String, Object> status = userSafeSourceStatus(response.getSourceStatus());
        status.put("cacheStatus", cacheStatus);
        status.remove("analysisJobStatus");
        if (job != null) status.put("analysisJobStatus", job.getStatus());
        boolean pending = job != null && List.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(job.getStatus());
        if (List.of("PREPARING", "UNAVAILABLE").contains(String.valueOf(status.get("dataState")))) {
            String state = pending ? "PREPARING" : "UNAVAILABLE";
            status.put("dataState", state);
            Map<String, Object> technical = new LinkedHashMap<>(response.getTechnicalAnalysis());
            technical.put("status", state);
            technical.put("reason", pending ? "分析正在后台更新" : "尚无可用分析结果");
            response.setTechnicalAnalysis(technical);
        }
        response.setSourceStatus(status);
        response.setFinancialWarnings(userFinancialWarnings(response.getFinancialWarnings()));
        return response;
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse updatePreference(Long userId, Long assetId,
                                                           HorizonProfileRequest request) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        horizonService.saveAssetOverride(userId, assetId, request);
        dataJobService.ensureRecoveryQueued(userId, assetId, asset.getProductId(), asset.getProductType(), true);
        detailCache.evict(userId, assetId);
        return detail(userId, assetId);
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse clearPreference(Long userId, Long assetId) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        horizonService.clearAssetOverride(userId, assetId);
        dataJobService.ensureRecoveryQueued(userId, assetId, asset.getProductId(), asset.getProductType(), true);
        detailCache.evict(userId, assetId);
        return detail(userId, assetId);
    }

    @Override
    public InvestmentAssetDetailResponse refresh(Long userId, Long assetId) {
        return rebuildAndCache(userId, assetId);
    }

    @Override
    public InvestmentAssetDetailResponse retryData(Long userId, Long assetId) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        dataJobService.ensureQueued(userId, assetId, asset.getProductId(), asset.getProductType(), true);
        detailCache.evict(userId, assetId);
        return detail(userId, assetId);
    }

    private InvestmentAssetDetailResponse rebuildAndCache(Long userId, Long assetId) {
        String holdingsBefore = holdingsRevision(userId);
        InvestmentAssetDetailResponse response = build(userId, assetId);
        DetailContext current = detailContext(userId, assetId);
        detailCache.evict(userId, assetId);
        Map<String, Object> provenance = asMap(response.getTechnicalAnalysis().get("provenance"));
        AnalysisEligibility currentEligibility = analysisEligibility(current.product(),
                detailHistory(current, loadQuotes(current.product())),
                findSnapshot(userId, assetId), current.horizon(), current.quality(), null);
        if ((current.snapshot() == null || currentEligibility.compatibleInputs()) && !currentEligibility.blocked()
                && Objects.equals(holdingsBefore, current.holdingsRevision())
                && Objects.equals(response.getSourceStatus().get("qualityDecision"), current.quality().get("decision"))
                && Objects.equals(provenance.get("datasetVersion"), current.quality().get("datasetVersion"))
                && Objects.equals(response.getSourceStatus().get("analyzedAt"),
                        current.snapshot() == null ? null : current.snapshot().getAnalyzedAt())
                && Objects.equals(response.getAsset(), current.asset())
                && Objects.equals(response.getAnalysisPreference(), horizonService.describe(current.horizon()))) {
            detailCache.put(userId, assetId, current.key(), response, current.checkedAt());
        }
        return response;
    }

    private boolean snapshotInputsMatch(
            InvestmentAnalysisSnapshot snapshot,
            List<ProductDailyQuote> quotes,
            Map<String, Object> latestQuality,
            ResolvedHorizonProfile horizonProfile,
            InvestmentProduct product) {
        if (snapshot == null || snapshot.getTechnicalJson() == null) {
            return false;
        }
        // 只有明确 BLOCK 才判为不可复用；校验信息缺失（数据源本次无新记录）保留既有分析。
        if ("BLOCK".equalsIgnoreCase(text(latestQuality.get("decision")))) {
            return false;
        }
        if (Boolean.TRUE.equals(snapshot.getHistoricalCache())
                || !("PASS".equals(snapshot.getQualityStatus()) || "WARN".equals(snapshot.getQualityStatus()))
                || snapshot.getDatasetVersion() == null
                || snapshot.getQualityRuleSetVersion() == null) {
            return false;
        }
        if (!Objects.equals(snapshot.getQualityRuleSetVersion(),
                text(latestQuality.get("qualityRuleSetVersion")))) {
            return false;
        }
        Map<String, Object> material = analysisCacheMaterial(
                snapshot.getDatasetVersion(),
                snapshot.getQualityRuleSetVersion(),
                horizonConfigJson(horizonProfile),
                runtimeProperties.getAnalysis().getStrategyVersion(),
                horizonProperties.getAnalysisRuleVersion(),
                product.getFundCategory(),
                product.getClassificationSource(),
                product.getClassificationVersion(),
                quoteHistoryMaterial(quotes),
                benchmarkCacheMaterial(product, quotes)
        );
        if (!snapshotKeyMatches(snapshot, material)) {
            return false;
        }
        LocalDate latestQuoteDate = quotes.isEmpty()
                ? null
                : quotes.get(quotes.size() - 1).getTradeDate();
        return latestQuoteDate == null
                || snapshot.getQuoteDate() == null
                || !snapshot.getQuoteDate().isBefore(latestQuoteDate);
    }

    private boolean snapshotKeyMatches(InvestmentAnalysisSnapshot snapshot, Map<String, Object> material) {
        if (snapshot == null) return false;
        if (Objects.equals(snapshot.getAnalysisCacheKey(), hash(writeFingerprintJson(material)))) return true;
        return readMap(snapshot.getHorizonConfigJson()).equals(readMap(text(material.get("horizonConfig"))))
                && legacySnapshotKeyMatches(snapshot, material, new LinkedHashMap<>());
    }

    private Map<String, Object> analysisCacheMaterial(
            String datasetVersion, String qualityRuleSetVersion, String horizonConfigJson,
            String strategyVersion, String analysisRuleVersion, String fundCategory,
            String classificationSource, String classificationVersion,
            Map<String, Object> quoteHistoryMaterial, Map<String, Object> benchmarkMaterial) {
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
        material.put("fundReturnBasis", "DIVIDEND_REINVESTED");
        material.put("fundBenchmarkAlignment", "COMMON_DATES_V1");
        return material;
    }

    private String horizonConfigJson(ResolvedHorizonProfile horizonProfile) {
        return writeFingerprintJson(horizonConfigMaterial(horizonProfile));
    }

    // Old keys hashed Map.of iteration order. Replay only the four summary fields (at most 24
    // orders), with the stored horizon JSON. Every input value, rule and benchmark still matches.
    // This avoids invalidating verified snapshots on deployment without weakening content checks.
    private boolean legacySnapshotKeyMatches(InvestmentAnalysisSnapshot snapshot, Map<String, Object> material,
                                              Map<String, Object> orderedSummary) {
        Map<String, Object> summary = asMap(material.get("quoteHistory"));
        if (orderedSummary.size() == summary.size()) {
            Map<String, Object> legacy = new LinkedHashMap<>(material);
            legacy.put("horizonConfig", snapshot.getHorizonConfigJson());
            legacy.put("quoteHistory", orderedSummary);
            return Objects.equals(snapshot.getAnalysisCacheKey(), hash(writeJson(legacy)));
        }
        for (var entry : summary.entrySet()) {
            if (orderedSummary.containsKey(entry.getKey())) continue;
            orderedSummary.put(entry.getKey(), entry.getValue());
            boolean matches = legacySnapshotKeyMatches(snapshot, material, orderedSummary);
            orderedSummary.remove(entry.getKey());
            if (matches) return true;
        }
        return false;
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

    private InvestmentAssetDetailResponse readOnlyDetail(Long userId, Long assetId, DetailContext context) {
        InvestmentAssetView asset = context.asset();
        InvestmentProduct product = context.product();
        ResolvedHorizonProfile horizonProfile = context.horizon();
        List<ProductDailyQuote> quotes = loadQuotes(product);
        AnalysisHistory history = detailHistory(context, quotes);
        List<ProductDailyQuote> analysisQuotes = history.records();
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        Map<String, Object> quality = context.quality();
        boolean localHistorySufficient = analysisQuotes.size()
                >= horizonProperties.getMinimumHistoryTradingDays();
        AnalysisEligibility eligibility = analysisEligibility(
                product, history, snapshot, horizonProfile, quality, null);
        boolean blocked = eligibility.blocked();
        boolean previousResult = !blocked && !eligibility.reusableSnapshot()
                && referenceSnapshotMatches(snapshot, product, quotes, horizonProfile, quality);
        // 只读：详情页不创建任务、不触发回补。数据刷新由后台调度负责。
        // Never label an incompatible snapshot (different preference/dataset/rules) as current.
        if (blocked || !eligibility.compatibleInputs() && !previousResult) snapshot = null;
        boolean pending = context.job() != null
                && List.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(context.job().getStatus());
        String unavailable = !localHistorySufficient ? "INSUFFICIENT" : pending ? "PREPARING" : "UNAVAILABLE";
        Map<String, Object> technical = snapshot == null
                ? new LinkedHashMap<>(Map.of("status", unavailable, "verdict", "WAIT",
                "reason", !localHistorySufficient ? "历史样本不足" : pending ? "分析正在后台更新" : "尚无可用分析结果"))
                : readMap(snapshot.getTechnicalJson());
        Map<String, Object> fundamental = snapshot == null
                ? new LinkedHashMap<>(Map.of("status", "INSUFFICIENT", "verdict", "WAIT"))
                : readMap(snapshot.getFundamentalJson());
        Map<String, Object> fund = snapshot == null ? new LinkedHashMap<>() : readMap(snapshot.getFundJson());
        if (blocked) {
            String gate = "BLOCKED";
            technical = new LinkedHashMap<>(Map.of("status", gate));
            fundamental = new LinkedHashMap<>(Map.of("status", gate));
        }
        WealthOverviewResponse wealth = wealthService.overview(userId);
        BigDecimal technicalScore = score(technical);
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        putFundClassification(sourceStatus, product);
        sourceStatus.put("dataState", blocked ? "BLOCKED"
                : eligibility.reusableSnapshot() ? "READY"
                : previousResult ? "STABLE_CACHE"
                : snapshot != null ? "WAITING"
                : unavailable);
        sourceStatus.put("qualityStatus", quality.getOrDefault("status", "UNAVAILABLE"));
        sourceStatus.put("qualityDecision", quality.getOrDefault("decision", "WAITING"));
        sourceStatus.put("quoteStatus", analysisQuotes.size() >= horizonProperties.getMinimumHistoryTradingDays()
                ? "READY" : "INSUFFICIENT");
        sourceStatus.put("adjustType", configuredAdjustType(product));
        sourceStatus.put("historicalCache", previousResult);
        sourceStatus.put("quoteDate", snapshot != null ? snapshot.getQuoteDate() : analysisQuotes.isEmpty()
                ? null : analysisQuotes.get(analysisQuotes.size() - 1).getTradeDate());
        sourceStatus.put("analyzedAt", snapshot == null ? null : snapshot.getAnalyzedAt());
        String datasetVersion = snapshot == null
                ? text(quality.get("datasetVersion")) : snapshot.getDatasetVersion();
        String analysisVersion = snapshot == null
                ? text(technical.get("strategyVersion"))
                : snapshot.getStrategyVersion();
        LocalDateTime calculatedAt = snapshot == null
                ? LocalDateTime.now(runtimeZone())
                : snapshot.getAnalyzedAt();
        InvestmentAssetDetailResponse response = new InvestmentAssetDetailResponse();
        response.setTechnicalAnalysis(technical);
        response.setFundamentalAnalysis(fundamental);
        Map<String, Object> warningSource = new LinkedHashMap<>(quality);
        if (blocked) {
            warningSource.put("decision", "BLOCK");
        }
        response.setFinancialWarnings(financialWarnings(
                asset, wealth, technicalScore,
                financialProfileMapper.selectByUserId(userId),
                technical, warningSource, horizonProfile.primaryCode()));
        response.setSourceStatus(sourceStatus);
        Object series = ("MUTUAL_FUND".equals(product.getProductType()) ? fund : technical).get("series");
        response.setQuoteSeries(displayQuoteSeries(quotes, series));
        return completeDetail(asset, horizonProfile, response,
                provenance(asset, horizonProfile.primaryCode(), datasetVersion, analysisVersion,
                        calculatedAt, "DETERMINISTIC_ANALYSIS"), text(quality.get("datasetVersion")));
    }

    private InvestmentAssetDetailResponse build(Long userId, Long assetId) {
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
        AnalysisHistory history = analysisHistory(product, quotes, requiredHistoryDays, Map.of());
        List<ProductDailyQuote> analysisQuotes = history.records();
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        putFundClassification(sourceStatus, product);
        sourceStatus.put("adjustType", configuredAdjustType(product));
        sourceStatus.put("requiredHistoryDays", requiredHistoryDays);
        sourceStatus.put("horizonProfileVersion", horizonProfile.version());
        sourceStatus.put("runtimeParameterVersion", runtimeProperties.getParameterVersion());
        sourceStatus.put("horizonWarnings", horizonProfile.warnings());
        sourceStatus.put("qualityConfigVersion", runtimeProperties.getDataQuality().getConfigVersion());

        InvestmentDataQualityService.Evaluation quality = null;
        try {
            quality = dataQualityService.preparedEvaluation(product, qualityStartDate, qualityEndDate, analysisQuotes);
        } catch (RuntimeException exception) {
            log.warn("Investment quality evaluation unavailable productId={} code={}",
                    product.getId(), product.getCode(), exception);
        }
        if (quality != null) {
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
            sourceStatus.put("qualityWindowRecordCount", quality.records().size());
        }
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        Map<String, Object> latestQuality = dataQualityService.analysisStatus(product,
                quality != null ? quality.datasetVersion() : snapshot == null ? null : snapshot.getDatasetVersion());
        Map<String, Object> inputScope = quality == null ? latestQuality : new LinkedHashMap<>();
        if (quality != null) inputScope.put("sampleStartDate", earliestRecordDate(quality.records()));
        history = analysisHistory(product, quotes, requiredHistoryDays, inputScope);
        analysisQuotes = history.records();
        sourceStatus.put("quoteCount", analysisQuotes.size());
        sourceStatus.put("quoteStatus", analysisQuotes.size() >= horizonProperties.getMinimumHistoryTradingDays()
                ? "READY" : "INSUFFICIENT");
        if (quality == null) {
            sourceStatus.put("qualityStatus", "UNAVAILABLE");
            sourceStatus.put("qualityDecision", "WAITING");
        }
        boolean fundReturnsMissing = history.missingReturns();
        boolean latestQualityBlocked = "BLOCK".equalsIgnoreCase(text(latestQuality.get("decision")));
        boolean sourceUnavailable = quality == null;
        AnalysisEligibility eligibility = analysisEligibility(
                product, history, snapshot, horizonProfile, latestQuality, quality);
        boolean inputsMismatch = quality != null && !quality.blocked() && !quality.matchesInputs(product, analysisQuotes);
        boolean explicitBlock = eligibility.blocked() || inputsMismatch;
        if (inputsMismatch) sourceStatus.put("analysisInputError", "已准备的数据与实际分析输入不一致");
        // 校验不可用时仅保留契约兼容、曾通过质量门控的快照；条数不能代替质量校验。
        boolean reusableWithoutEvaluation = sourceUnavailable && !explicitBlock && eligibility.reusableSnapshot();
        boolean qualityBlocked = explicitBlock || sourceUnavailable && !reusableWithoutEvaluation;
        if (latestQualityBlocked) {
            sourceStatus.put("qualityStatus", latestQuality.getOrDefault("status", "BLOCKED"));
            sourceStatus.put("qualityDecision", "BLOCK");
        }
        if (fundReturnsMissing) sourceStatus.put("fundReturnStatus", "TOTAL_RETURN_MISSING");
        if (qualityBlocked) {
            sourceStatus.put("analysisGate", explicitBlock ? "BLOCKED" : "WAITING");
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
            if (sourceUnavailable) {
                // 轻量提示：结论基于本地已落库历史，同步仍在后台进行。
                sourceStatus.put("syncPending", true);
            }
        }

        String horizonConfigJson = horizonConfigJson(horizonProfile);
        String preferenceHash = hash(horizonConfigJson);
        LocalDate quoteDate = quality == null || quality.records().isEmpty()
                ? analysisQuotes.isEmpty() ? null
                    : analysisQuotes.get(analysisQuotes.size() - 1).getTradeDate()
                : latestRecordDate(quality.records());
        Map<String, Object> fundBenchmark = qualityBlocked || sourceUnavailable
                ? Map.of()
                : benchmarkPayload(product, analysisQuotes.stream()
                    .map(InvestmentAnalysisServiceImpl::quoteRecord).toList());
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
        String configuredStrategyVersion = runtimeProperties.getAnalysis().getStrategyVersion();
        String effectiveDatasetVersion = quality == null
                ? reusableWithoutEvaluation ? snapshot.getDatasetVersion() : null
                : quality.datasetVersion();
        String effectiveRuleSetVersion = quality == null
                ? reusableWithoutEvaluation ? snapshot.getQualityRuleSetVersion() : null
                : quality.ruleSetVersion();
        Map<String, Object> cacheMaterial = analysisCacheMaterial(
                effectiveDatasetVersion,
                effectiveRuleSetVersion,
                horizonConfigJson,
                configuredStrategyVersion,
                horizonProperties.getAnalysisRuleVersion(),
                product.getFundCategory(),
                product.getClassificationSource(),
                product.getClassificationVersion(),
                quoteHistoryMaterial(analysisQuotes),
                benchmarkCacheMaterial(fundBenchmark)
        );
        String analysisCacheKey = qualityBlocked ? null
                : reusableWithoutEvaluation || snapshotKeyMatches(snapshot, cacheMaterial)
                    ? snapshot.getAnalysisCacheKey() : hash(writeFingerprintJson(cacheMaterial));
        boolean currentSnapshot = !qualityBlocked
                && isCurrentSnapshot(snapshot, analysisCacheKey);
        boolean compatibleSnapshot = currentSnapshot
                && isCompatibleSnapshot(snapshot, analysisCacheKey);
        boolean cacheHit = compatibleSnapshot;
        sourceStatus.put("datasetVersion", effectiveDatasetVersion);
        sourceStatus.put("qualityRuleSetVersion", effectiveRuleSetVersion);
        Map<String, Object> technical;
        Map<String, Object> fundamental;
        Map<String, Object> fund;
        boolean historicalCacheUsed = false;
        if (qualityBlocked) {
            String gateStatus = explicitBlock ? "BLOCKED" : "UNAVAILABLE";
            log.warn("Investment analysis pending productId={} code={} qualityDecision={} missingFundReturns={}",
                    product.getId(), product.getCode(), latestQuality.get("decision"), fundReturnsMissing);
            String reason = "最新分析暂未就绪";
            technical = Map.of("status", gateStatus, "reason", reason);
            fundamental = Map.of("status", gateStatus, "reason", reason);
            fund = "MUTUAL_FUND".equals(product.getProductType())
                    ? Map.of("status", gateStatus, "reason", reason)
                    : Map.of();
            sourceStatus.put("analysisStatus", gateStatus);
            sourceStatus.put("historicalCache", false);
        } else if (cacheHit) {
            technical = readMap(snapshot.getTechnicalJson());
            fundamental = readMap(snapshot.getFundamentalJson());
            fund = readMap(snapshot.getFundJson());
            if (Boolean.TRUE.equals(snapshot.getHistoricalCache())) {
                snapshot.setHistoricalCache(false);
                snapshot.setAnalysisStatus("READY");
                snapshotMapper.updateById(snapshot);
            }
        } else {
            List<Map<String, Object>> records = "MUTUAL_FUND".equals(product.getProductType())
                    ? quoteRecords(analysisQuotes)
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
                    requireStrategyVersion(configuredStrategyVersion, technical, "分析");
                } else {
                    try {
                        technical = analysisClient.technicalAnalysis(
                                records, horizons, horizonProfile.primaryCode(), marketSnapshot(asset));
                        requireStrategyVersion(configuredStrategyVersion, technical, "分析");
                    } catch (RuntimeException technicalException) {
                        log.warn("Technical analysis failed for product {}", product.getId(), technicalException);
                        technical = Map.of("status", "FAILED", "verdict", "WAIT",
                                "message", "技术分析服务暂不可用");
                        sourceStatus.put("analysisError", technicalException.getMessage());
                    }
                    try {
                        fundamental = analysisClient.fundamentalAnalysis(product.getCode(), product.getMarket());
                    } catch (RuntimeException financialException) {
                        fundamental = Map.of("status", "UNAVAILABLE", "verdict", "INSUFFICIENT",
                                "reason", "财务数据源暂不可用，基本面分析待更新");
                        sourceStatus.put("fundamentalError", financialException.getMessage());
                    }
                    fund = Map.of();
                }
                if (!"READY".equals(analysisResultStatus(technical)) && snapshot != null
                        && "READY".equals(snapshot.getAnalysisStatus())) {
                    historicalCacheUsed = compatibleSnapshot
                            || referenceSnapshotMatches(snapshot, product, quotes, horizonProfile, latestQuality);
                    if (historicalCacheUsed) {
                        technical = readMap(snapshot.getTechnicalJson());
                        fundamental = readMap(snapshot.getFundamentalJson());
                        fund = readMap(snapshot.getFundJson());
                    }
                    sourceStatus.put("analysisStatus", historicalCacheUsed ? "CACHED" : analysisResultStatus(technical));
                    sourceStatus.put("historicalCache", historicalCacheUsed);
                } else {
                    sourceStatus.put("analysisStrategyVersion", technical.get("strategyVersion"));
                    sourceStatus.put("analysisStatus", analysisResultStatus(technical));
                    snapshot = saveSnapshot(userId, assetId, snapshot, quoteDate, preferenceHash,
                            horizonProfile.version(), horizonConfigJson,
                            effectiveDatasetVersion, effectiveRuleSetVersion, configuredStrategyVersion,
                            analysisCacheKey, quality.status(),
                            technical, fundamental, fund, sourceStatus);
                    currentSnapshot = true;
                    compatibleSnapshot = isCompatibleSnapshot(snapshot, analysisCacheKey);
                }
            } catch (RuntimeException exception) {
                if (!compatibleSnapshot && !referenceSnapshotMatches(snapshot, product, quotes, horizonProfile, latestQuality)) {
                    boolean fundProduct = "MUTUAL_FUND".equals(product.getProductType());
                    technical = fundProduct
                            ? Map.of(
                                    "status", "FAILED",
                                    "verdict", "WAIT",
                                    "action", "WAIT",
                                    "adviceStatus", "UNAVAILABLE",
                                    "reasonCode", "ANALYSIS_SERVICE_UNAVAILABLE",
                                    "reason", "最新分析暂未就绪")
                            : Map.of("status", "FAILED", "verdict", "WAIT",
                                    "message", "技术分析服务暂不可用");
                    fundamental = Map.of("status", "INSUFFICIENT", "verdict", "INSUFFICIENT");
                    fund = fundProduct ? technical : Map.of();
                } else {
                    technical = readMap(snapshot.getTechnicalJson());
                    fundamental = readMap(snapshot.getFundamentalJson());
                    fund = readMap(snapshot.getFundJson());
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
        List<Map<String, Object>> warnings = financialWarnings(
                asset, wealth, score,
                financialProfileMapper.selectByUserId(userId),
                technical, sourceStatus, horizonProfile.primaryCode()
        );
        sourceStatus.putIfAbsent("analysisStatus", "READY");
        sourceStatus.put("cacheHit", cacheHit);
        sourceStatus.putIfAbsent("historicalCache", false);
        sourceStatus.put("ruleVersion", horizonProperties.getAnalysisRuleVersion());
        sourceStatus.put("strategyVersion", configuredStrategyVersion);
        sourceStatus.put("quoteDate", historicalCacheUsed ? snapshot.getQuoteDate() : quoteDate);
        sourceStatus.put("analyzedAt", currentSnapshot || historicalCacheUsed ? snapshot.getAnalyzedAt() : null);
        boolean reliableCache = Boolean.TRUE.equals(sourceStatus.get("historicalCache"));
        sourceStatus.put("dataState", explicitBlock
                ? "BLOCKED"
                : qualityBlocked
                ? "WAITING"
                : reliableCache
                ? "STABLE_CACHE"
                : "FAILED".equals(sourceStatus.get("analysisStatus"))
                ? "WAITING"
                : !"READY".equals(text(sourceStatus.get("analysisStatus")))
                ? text(sourceStatus.get("analysisStatus"))
                : "READY");
        String datasetVersion = historicalCacheUsed ? snapshot.getDatasetVersion() : text(sourceStatus.get("datasetVersion"));
        String analysisVersion = !currentSnapshot || qualityBlocked
                ? text(technical.get("strategyVersion"))
                : snapshot.getStrategyVersion();
        LocalDateTime calculatedAt = (!currentSnapshot && !historicalCacheUsed) || qualityBlocked
                ? LocalDateTime.now(runtimeZone())
                : snapshot.getAnalyzedAt();
        InvestmentAssetDetailResponse response = new InvestmentAssetDetailResponse();
        response.setTechnicalAnalysis(technical);
        response.setFundamentalAnalysis(fundamental);
        response.setFinancialWarnings(warnings);
        response.setSourceStatus(sourceStatus);
        Object series = ("MUTUAL_FUND".equals(product.getProductType()) ? fund : technical).get("series");
        response.setQuoteSeries(displayQuoteSeries(quotes, series));
        return completeDetail(asset, horizonProfile, response,
                provenance(asset, horizonProfile.primaryCode(), datasetVersion, analysisVersion,
                        calculatedAt, "DETERMINISTIC_ANALYSIS"), datasetVersion);
    }

    private InvestmentAssetDetailResponse completeDetail(
            InvestmentAssetView asset, ResolvedHorizonProfile horizonProfile,
            InvestmentAssetDetailResponse response, Map<String, Object> provenance,
            String disclaimerDatasetVersion) {
        response.setAsset(asset);
        response.setTechnicalAnalysis(withProvenance(response.getTechnicalAnalysis(), provenance));
        response.setFundamentalAnalysis(withProvenance(response.getFundamentalAnalysis(), provenance));
        response.setDisclaimer(disclaimer(asset, horizonProfile.primaryCode(), disclaimerDatasetVersion));
        response.setSourceStatus(userSafeSourceStatus(response.getSourceStatus()));
        response.setAnalysisPreference(horizonService.describe(horizonProfile));
        return response;
    }

    private static Map<String, Object> userSafeSourceStatus(Map<String, Object> sourceStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "dataState", "quoteStatus", "adjustType", "historicalCache", "quoteDate", "analyzedAt",
                "qualityStatus", "qualityDecision", "analysisGate",
                "analysisStatus", "analysisJobStatus", "fundTypeRaw", "fundCategory", "classificationSource",
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
        return quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, configuredAdjustType(product))
                .orderByAsc(ProductDailyQuote::getTradeDate));
    }

    private record AnalysisHistory(List<ProductDailyQuote> records, boolean missingReturns, boolean internalGap) { }

    private record AnalysisEligibility(boolean compatibleInputs, boolean reusableSnapshot, boolean blocked) { }

    /** A previous result is displayable only while its original inputs still match, including corrections. */
    private boolean referenceSnapshotMatches(InvestmentAnalysisSnapshot snapshot, InvestmentProduct product,
            List<ProductDailyQuote> quotes, ResolvedHorizonProfile horizon, Map<String, Object> quality) {
        if (snapshot == null || snapshot.getQuoteDate() == null || !"READY".equals(snapshot.getAnalysisStatus())) return false;
        var original = analysisHistory(product, quotes.stream()
                .filter(quote -> !quote.getTradeDate().isAfter(snapshot.getQuoteDate())).toList(),
                interactiveHistoryDays(horizon, horizonProperties), quality);
        return !original.internalGap() && snapshotInputsMatch(snapshot, original.records(), quality, horizon, product);
    }

    private AnalysisEligibility analysisEligibility(
            InvestmentProduct product, AnalysisHistory history, InvestmentAnalysisSnapshot snapshot,
            ResolvedHorizonProfile horizon, Map<String, Object> qualityStatus,
            InvestmentDataQualityService.Evaluation evaluation) {
        boolean compatibleInputs = !history.internalGap()
                && snapshotInputsMatch(snapshot, history.records(), qualityStatus, horizon, product);
        boolean reusable = compatibleInputs && "READY".equals(snapshot.getAnalysisStatus());
        boolean validatedPrefix = evaluation != null && !evaluation.blocked() && !evaluation.records().isEmpty()
                && !history.records().isEmpty() && latestRecordDate(evaluation.records())
                .equals(history.records().get(history.records().size() - 1).getTradeDate());
        boolean returnsBlocked = history.missingReturns()
                && (history.internalGap() || (evaluation == null ? !reusable : !validatedPrefix));
        boolean blocked = returnsBlocked || "BLOCK".equalsIgnoreCase(text(qualityStatus.get("decision")))
                || evaluation != null && evaluation.blocked();
        return new AnalysisEligibility(compatibleInputs, reusable, blocked);
    }

    private static AnalysisHistory analysisHistory(
            InvestmentProduct product, List<ProductDailyQuote> quotes, int requiredHistoryDays, Map<String, Object> quality) {
        boolean fund = "MUTUAL_FUND".equals(product.getProductType());
        int publishedEnd = quotes.size();
        if (fund) {
            // An unpublished return tail must not displace the validated window or its baseline.
            while (publishedEnd > 0) {
                BigDecimal totalReturn = quotes.get(publishedEnd - 1).getTotalReturnIndex();
                if (totalReturn != null && totalReturn.signum() > 0) break;
                publishedEnd--;
            }
        }
        boolean unpublishedTail = publishedEnd < quotes.size();
        // Limit analysis before validation; the complete stored history still feeds the chart.
        quotes = quotes.subList(Math.max(0, publishedEnd - requiredHistoryDays), publishedEnd);
        // Within the configured window, missing fund returns cannot be deleted to splice observations.
        int prefix = quotes.size();
        boolean gap = false;
        if (fund) {
            for (int index = 0; index < quotes.size(); index++) {
                ProductDailyQuote quote = quotes.get(index);
                if (quote.getTotalReturnIndex() == null || quote.getTotalReturnIndex().signum() <= 0) {
                    prefix = Math.min(prefix, index);
                } else if (prefix < index) {
                    gap = true;
                }
            }
        }
        AnalysisHistory history = new AnalysisHistory(
                quotes.subList(0, prefix), unpublishedTail || prefix < quotes.size(), gap);
        Object start = quality.get("sampleStartDate");
        if (start == null) return history;
        LocalDate first = LocalDate.parse(String.valueOf(start));
        return new AnalysisHistory(history.records().stream().filter(quote -> !quote.getTradeDate().isBefore(first)).toList(),
                history.missingReturns(), history.internalGap());
    }

    private InvestmentAnalysisSnapshot findSnapshot(Long userId, Long assetId) {
        return findSnapshot(userId, assetId, false);
    }

    private InvestmentAnalysisSnapshot findSnapshot(Long userId, Long assetId, boolean metadataOnly) {
        LambdaQueryWrapper<InvestmentAnalysisSnapshot> query = new LambdaQueryWrapper<InvestmentAnalysisSnapshot>()
                .eq(InvestmentAnalysisSnapshot::getUserId, userId)
                .eq(InvestmentAnalysisSnapshot::getAssetId, assetId)
                .eq(InvestmentAnalysisSnapshot::getRuleVersion,
                        horizonProperties.getAnalysisRuleVersion()).last("LIMIT 1");
        if (metadataOnly) {
            query.select(InvestmentAnalysisSnapshot::getId, InvestmentAnalysisSnapshot::getAnalysisCacheKey,
                    InvestmentAnalysisSnapshot::getDatasetVersion,
                    InvestmentAnalysisSnapshot::getAnalyzedAt,
                    InvestmentAnalysisSnapshot::getAnalysisStatus, InvestmentAnalysisSnapshot::getUpdatedAt);
        }
        return snapshotMapper.selectOne(query);
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
        snapshot.setSourceStatusJson(writeJson(sourceStatus));
        snapshot.setAnalysisStatus(analysisResultStatus(technical));
        // MySQL analyzed_at is DATETIME without fractional seconds.
        snapshot.setAnalyzedAt(LocalDateTime.now().withNano(0));
        if (snapshot.getId() == null) snapshotMapper.insert(snapshot); else snapshotMapper.updateById(snapshot);
        return snapshot;
    }

    private List<Map<String, Object>> quoteRecords(List<ProductDailyQuote> quotes) {
        return quotes.stream().map(InvestmentAnalysisServiceImpl::quoteRecord).toList();
    }

    private Map<String, Object> quoteHistoryMaterial(List<ProductDailyQuote> quotes) {
        if (quotes.isEmpty()) return Map.of("count", 0);
        List<ProductDailyQuote> ordered = quotes.stream()
                .sorted(Comparator.comparing(ProductDailyQuote::getTradeDate)).toList();
        List<Map<String, Object>> inputs = ordered.stream().map(quote -> {
            Map<String, Object> record = quoteRecord(quote);
            record.put("adjustType", quote.getAdjustType());
            return record;
        }).toList();
        return Map.of(
                "count", ordered.size(),
                "startDate", ordered.get(0).getTradeDate().toString(),
                "endDate", ordered.get(ordered.size() - 1).getTradeDate().toString(),
                "inputHash", hash(writeJson(inputs))
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

    private static Map<String, Object> quoteRecord(ProductDailyQuote quote) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data_date", quote.getTradeDate().toString());
        item.put("open", quote.getOpenPrice());
        item.put("high", quote.getHighPrice());
        item.put("low", quote.getLowPrice());
        item.put("close", quote.getClosePrice());
        item.put("total_return_index", quote.getTotalReturnIndex());
        item.put("volume", quote.getVolume());
        if (quote.getAmount() != null) item.put("amount", quote.getAmount());
        if (quote.getTurnoverRate() != null) item.put("turnover_rate", quote.getTurnoverRate());
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
        return dataQualityService.adjustType(product);
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
                benchmarkPayload(benchmarkProfileService.resolveCached(
                        product.getProductType(), product.getCode(), endDate, startDate, endDate))
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
        String resolutionReason = null;
        if (profile == null || !Objects.equals(product.getCode(), profile.getProductCode())) {
            try {
                AnalysisServiceClient.ResolvedProduct resolved = analysisClient.resolveProduct(
                        product.getProductType(), product.getCode()
                );
                benchmarkProfileService.configureImportedFundBenchmark(product, resolved);
                resolutionReason = resolved.benchmarkResolutionReason();
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
                    resolutionReason == null || resolutionReason.isBlank()
                            ? "未配置与当前基金代码精确匹配的官方基准"
                            : resolutionReason
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
        if (benchmark.get("records") instanceof List<?> records) {
            material.put("recordCount", records.size());
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
            InvestmentAssetView asset,
            WealthOverviewResponse wealth,
            BigDecimal technicalScore,
            FinancialProfile profile,
            Map<String, Object> technical,
            Map<String, Object> source,
            String horizonCode
    ) {
        Map<String, Object> fullHistory = asMap(technical.get("fullHistory"));
        return userFinancialWarnings(warningEngine.evaluate(new InvestmentFinancialWarningEngine.Input(
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
                        technical.get("maxDrawdown")
                ),
                firstText(
                        source.get("qualityDecision"),
                        source.get("decision")
                ),
                horizonCode,
                text(source.get("datasetVersion"))
        )));
    }

    static List<Map<String, Object>> userFinancialWarnings(List<Map<String, Object>> warnings) {
        // 质量诊断属于后台维护，不能混入用户的财务风险提醒。
        return warnings.stream().filter(warning -> !"DATA_INCOMPLETE".equals(warning.get("code"))).toList();
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
            Map<String, Object> provenance
    ) {
        Map<String, Object> result = new LinkedHashMap<>(
                source == null ? Map.of() : source
        );
        result.put("provenance", new LinkedHashMap<>(provenance));
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

    private ZoneId runtimeZone() {
        ZoneId zone = runtimeProperties.getMarket().getZone();
        return zone == null ? ZoneId.systemDefault() : zone;
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

    private String writeFingerprintJson(Object value) {
        try {
            return fingerprintWriter.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("分析缓存指纹序列化失败", exception);
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
        // The trading-day capacity also needs the preceding observation for its return baseline.
        return (int) Math.min(Integer.MAX_VALUE,
                Math.min((long) properties.getMaxHistoryTradingDays() + 1, required));
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

}
