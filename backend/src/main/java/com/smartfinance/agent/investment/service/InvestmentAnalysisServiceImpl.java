package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.*;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class InvestmentAnalysisServiceImpl implements InvestmentAnalysisService {

    static final String RULE_VERSION = "technical-v2";

    private final InvestmentAssetService assetService;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentHorizonService horizonService;
    private final InvestmentHorizonProperties horizonProperties;
    private final InvestmentAnalysisSnapshotMapper snapshotMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentSyncWorker syncWorker;
    private final WealthService wealthService;
    private final FinancialProfileMapper financialProfileMapper;
    private final InvestmentAiExplanationService aiExplanationService;
    private final ObjectMapper objectMapper;
    private final PersonalizedActionCalculator actionCalculator = new PersonalizedActionCalculator();

    public InvestmentAnalysisServiceImpl(InvestmentAssetService assetService,
                                         InvestmentProductMapper productMapper,
                                         ProductDailyQuoteMapper quoteMapper,
                                         InvestmentHorizonService horizonService,
                                         InvestmentHorizonProperties horizonProperties,
                                         InvestmentAnalysisSnapshotMapper snapshotMapper,
                                         AnalysisServiceClient analysisClient,
                                         InvestmentSyncWorker syncWorker,
                                         WealthService wealthService,
                                         FinancialProfileMapper financialProfileMapper,
                                         InvestmentAiExplanationService aiExplanationService,
                                         ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.horizonService = horizonService;
        this.horizonProperties = horizonProperties;
        this.snapshotMapper = snapshotMapper;
        this.analysisClient = analysisClient;
        this.syncWorker = syncWorker;
        this.wealthService = wealthService;
        this.financialProfileMapper = financialProfileMapper;
        this.aiExplanationService = aiExplanationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public InvestmentAssetDetailResponse detail(Long userId, Long assetId) {
        return build(userId, assetId, false, false);
    }

    @Override
    public InvestmentAssetDetailResponse analysis(Long userId, Long assetId) {
        return build(userId, assetId, false, false);
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse updatePreference(Long userId, Long assetId,
                                                           HorizonProfileRequest request) {
        assetService.get(userId, assetId);
        horizonService.saveAssetOverride(userId, assetId, request);
        return build(userId, assetId, true, false);
    }

    @Override
    @Transactional
    public InvestmentAssetDetailResponse clearPreference(Long userId, Long assetId) {
        assetService.get(userId, assetId);
        horizonService.clearAssetOverride(userId, assetId);
        return build(userId, assetId, true, false);
    }

    @Override
    public InvestmentAssetDetailResponse refresh(Long userId, Long assetId) {
        return build(userId, assetId, true, true);
    }

    private InvestmentAssetDetailResponse build(Long userId, Long assetId,
                                                boolean forceAnalysis,
                                                boolean refreshQuotes) {
        InvestmentAssetView asset = assetService.get(userId, assetId);
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null) throw new IllegalArgumentException("投资产品不存在");
        ResolvedHorizonProfile horizonProfile = horizonService.resolve(userId, assetId);
        Map<String, List<Integer>> horizons = horizonProfile.analysisRanges();
        int requiredHistoryDays = requiredHistoryDays(
                horizonProfile, horizonProperties.getMaxHistoryTradingDays());
        List<ProductDailyQuote> quotes = loadQuotes(product);
        Map<String, Object> sourceStatus = new LinkedHashMap<>();
        sourceStatus.put("quoteStatus", quotes.size() >= 20 ? "READY" : "INSUFFICIENT");
        sourceStatus.put("quoteCount", quotes.size());
        sourceStatus.put("adjustType", "STOCK".equals(product.getProductType()) ? "QFQ" : "NONE");
        sourceStatus.put("requiredHistoryDays", requiredHistoryDays);
        sourceStatus.put("horizonProfileVersion", horizonProfile.version());
        sourceStatus.put("horizonWarnings", horizonProfile.warnings());
        if (shouldRefreshQuotes(quotes.size(), requiredHistoryDays, refreshQuotes)) {
            try {
                Map<String, Object> response = analysisClient.dailyQuotes(
                        product, LocalDate.now().minusDays(calendarLookbackDays(requiredHistoryDays)), LocalDate.now());
                syncWorker.persistDailyQuotes(product, response);
                quotes = loadQuotes(product);
                sourceStatus.put("quoteCount", quotes.size());
                sourceStatus.put("quoteStatus", quotes.size() >= 20 ? "READY" : "INSUFFICIENT");
                sourceStatus.put("quoteProvider", response.get("provider"));
                sourceStatus.put("quoteWarnings", response.getOrDefault("warnings", List.of()));
            } catch (RuntimeException exception) {
                sourceStatus.put("quoteStatus", quotes.isEmpty() ? "FAILED" : "CACHED");
                sourceStatus.put("quoteError", exception.getMessage());
            }
        }

        String horizonConfigJson = writeJson(Map.of(
                "version", horizonProfile.version(),
                "primaryHorizon", horizonProfile.primaryCode(),
                "horizons", horizons));
        String preferenceHash = hash(horizonConfigJson);
        LocalDate quoteDate = quotes.isEmpty() ? null : quotes.get(quotes.size() - 1).getTradeDate();
        InvestmentAnalysisSnapshot snapshot = findSnapshot(userId, assetId);
        boolean cacheHit = !forceAnalysis && snapshot != null
                && Objects.equals(snapshot.getQuoteDate(), quoteDate)
                && Objects.equals(snapshot.getPreferenceHash(), preferenceHash);
        Map<String, Object> technical;
        Map<String, Object> fundamental;
        Map<String, Object> backtest;
        Map<String, Object> fund;
        if (cacheHit) {
            technical = readMap(snapshot.getTechnicalJson());
            fundamental = readMap(snapshot.getFundamentalJson());
            fund = readMap(snapshot.getFundJson());
            backtest = readMap(snapshot.getBacktestJson());
        } else {
            List<Map<String, Object>> records = quoteRecords(quotes);
            try {
                if ("MUTUAL_FUND".equals(product.getProductType())) {
                    fund = analysisClient.fundAnalysis(records);
                    technical = fund;
                    fundamental = Map.of("status", "NOT_APPLICABLE", "verdict", "NOT_APPLICABLE");
                } else {
                    technical = analysisClient.technicalAnalysis(
                            records, horizons, horizonProfile.primaryCode());
                    fundamental = analysisClient.fundamentalAnalysis(product.getCode(), product.getMarket());
                    fund = Map.of();
                }
                backtest = records.size() >= 40 ? analysisClient.backtest(records, horizons)
                        : Map.of("status", "INSUFFICIENT", "occurrences", 0);
                snapshot = saveSnapshot(userId, assetId, snapshot, quoteDate, preferenceHash,
                        horizonProfile.version(), horizonConfigJson,
                        technical, fundamental, fund, backtest, sourceStatus);
            } catch (RuntimeException exception) {
                if (snapshot == null) {
                    technical = Map.of("status", "FAILED", "verdict", "WAIT",
                            "message", "技术分析服务暂不可用");
                    fundamental = Map.of("status", "INSUFFICIENT", "verdict", "INSUFFICIENT");
                    fund = Map.of();
                    backtest = Map.of("status", "FAILED", "occurrences", 0);
                } else {
                    technical = readMap(snapshot.getTechnicalJson());
                    fundamental = readMap(snapshot.getFundamentalJson());
                    fund = readMap(snapshot.getFundJson());
                    backtest = readMap(snapshot.getBacktestJson());
                }
                sourceStatus.put("analysisStatus", snapshot == null ? "FAILED" : "CACHED");
                sourceStatus.put("analysisError", exception.getMessage());
            }
        }

        WealthOverviewResponse wealth = wealthService.overview(userId);
        asset = assetService.get(userId, assetId);
        BigDecimal score = score(technical, product.getProductType());
        PersonalizedActionCalculator.Result action = actionCalculator.calculate(
                product.getProductType(), score, asset.getLatestPrice(), wealth.getInvestmentCash(), asset.getQuantity());
        Map<String, Object> actionMap = objectMapper.convertValue(action, new TypeReference<>() { });
        if (technical.get("actionZones") instanceof Map<?, ?> zones) {
            actionMap.put("priceZones", zones);
        }
        if (snapshot != null && snapshot.getId() != null) {
            String combinedSignalHash = hash(writeJson(Map.of(
                    "technical", Map.of(
                            "score", technical.getOrDefault("score", 50),
                            "verdict", technical.getOrDefault("verdict", technical.getOrDefault("action", "WAIT")),
                            "zones", technical.getOrDefault("actionZones", Map.of())),
                    "personalizedAction", actionMap
            )));
            boolean signalChanged = invalidateStaleExplanation(snapshot, combinedSignalHash);
            boolean explanationNeedsRefresh = signalChanged
                    || snapshot.getAiExplanation() == null || snapshot.getAiExplanation().isBlank();
            if (signalChanged) {
                snapshotMapper.updateById(snapshot);
            }
            if (explanationNeedsRefresh) {
                aiExplanationService.refreshIfAllowed(snapshot.getId(), combinedSignalHash, product.getName(),
                        writeJson(technical), writeJson(fundamental), writeJson(actionMap));
            }
        }
        List<Map<String, Object>> warnings = financialWarnings(asset, wealth, score,
                financialProfileMapper.selectByUserId(userId));

        sourceStatus.putIfAbsent("analysisStatus", "READY");
        sourceStatus.put("cacheHit", cacheHit);
        sourceStatus.put("ruleVersion", RULE_VERSION);
        sourceStatus.put("quoteDate", quoteDate);
        sourceStatus.put("analyzedAt", snapshot == null ? null : snapshot.getAnalyzedAt());
        InvestmentAssetDetailResponse response = new InvestmentAssetDetailResponse();
        response.setAsset(asset);
        response.setTechnicalAnalysis(technical);
        response.setFundamentalAnalysis(fundamental);
        response.setPersonalizedAction(actionMap);
        response.setFinancialWarnings(warnings);
        response.setBacktestSummary(backtest);
        response.setAiExplanation(aiExplanation(snapshot, technical, fundamental, product));
        response.setSourceStatus(sourceStatus);
        response.setAnalysisPreference(horizonService.describe(horizonProfile));
        Object series = ("MUTUAL_FUND".equals(product.getProductType()) ? fund : technical).get("series");
        if (series instanceof List<?> list) {
            response.setQuoteSeries(list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList());
        } else {
            response.setQuoteSeries(quoteRecords(quotes));
        }
        return response;
    }

    private List<ProductDailyQuote> loadQuotes(InvestmentProduct product) {
        List<ProductDailyQuote> all = quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .orderByAsc(ProductDailyQuote::getTradeDate));
        Map<LocalDate, ProductDailyQuote> byDate = new TreeMap<>();
        for (ProductDailyQuote quote : all) {
            ProductDailyQuote current = byDate.get(quote.getTradeDate());
            if (current == null || "QFQ".equals(quote.getAdjustType())) {
                byDate.put(quote.getTradeDate(), quote);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private InvestmentAnalysisSnapshot findSnapshot(Long userId, Long assetId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<InvestmentAnalysisSnapshot>()
                .eq(InvestmentAnalysisSnapshot::getUserId, userId)
                .eq(InvestmentAnalysisSnapshot::getAssetId, assetId)
                .eq(InvestmentAnalysisSnapshot::getRuleVersion, RULE_VERSION).last("LIMIT 1"));
    }

    private InvestmentAnalysisSnapshot saveSnapshot(Long userId, Long assetId,
                                                      InvestmentAnalysisSnapshot snapshot,
                                                      LocalDate quoteDate, String preferenceHash,
                                                      String horizonProfileVersion,
                                                      String horizonConfigJson,
                                                      Map<String, Object> technical,
                                                      Map<String, Object> fundamental,
                                                      Map<String, Object> fund,
                                                      Map<String, Object> backtest,
                                                      Map<String, Object> sourceStatus) {
        if (snapshot == null) {
            snapshot = new InvestmentAnalysisSnapshot();
            snapshot.setUserId(userId);
            snapshot.setAssetId(assetId);
            snapshot.setRuleVersion(RULE_VERSION);
        }
        snapshot.setPreferenceHash(preferenceHash);
        snapshot.setHorizonProfileVersion(horizonProfileVersion);
        snapshot.setHorizonConfigJson(horizonConfigJson);
        snapshot.setQuoteDate(quoteDate);
        snapshot.setTechnicalJson(writeJson(technical));
        snapshot.setFundamentalJson(writeJson(fundamental));
        snapshot.setFundJson(writeJson(fund));
        snapshot.setBacktestJson(writeJson(backtest));
        snapshot.setSourceStatusJson(writeJson(sourceStatus));
        snapshot.setAnalysisStatus("READY");
        snapshot.setAnalyzedAt(LocalDateTime.now());
        if (snapshot.getId() == null) snapshotMapper.insert(snapshot); else snapshotMapper.updateById(snapshot);
        return snapshot;
    }

    private List<Map<String, Object>> quoteRecords(List<ProductDailyQuote> quotes) {
        return quotes.stream().map(quote -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("data_date", quote.getTradeDate().toString());
            item.put("open", quote.getOpenPrice());
            item.put("high", quote.getHighPrice());
            item.put("low", quote.getLowPrice());
            item.put("close", quote.getClosePrice());
            item.put("volume", quote.getVolume());
            return item;
        }).toList();
    }

    private static BigDecimal score(Map<String, Object> technical, String productType) {
        Object value = technical.get("score");
        if (value != null) return new BigDecimal(String.valueOf(value));
        String action = String.valueOf(technical.getOrDefault("action", "HOLD"));
        return switch (action) {
            case "ACCUMULATE" -> new BigDecimal("75");
            case "PAUSE" -> new BigDecimal("35");
            case "TAKE_PROFIT" -> new BigDecimal("60");
            default -> new BigDecimal("50");
        };
    }

    private List<Map<String, Object>> financialWarnings(InvestmentAssetView asset,
                                                         WealthOverviewResponse wealth,
                                                         BigDecimal technicalScore,
                                                         FinancialProfile profile) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (!wealth.isInitialized()) {
            warnings.add(warning("WEALTH_NOT_INITIALIZED", "INFO", "尚未建立现金基准，数量建议仅按投资账户现金计算"));
        }
        if (profile != null && wealth.isInitialized() && profile.getFixedExpense() != null) {
            BigDecimal reserveTarget = profile.getFixedExpense().multiply(new BigDecimal("3"));
            if (wealth.getDailyCash().compareTo(reserveTarget) < 0) {
                warnings.add(warning("RESERVE_LOW", "WARNING", "日常现金低于约 3 个月固定支出，请先关注备用金"));
            }
        }
        if (wealth.getTotalAssets() != null && wealth.getTotalAssets().signum() > 0
                && asset.getMarketValueCny() != null) {
            BigDecimal concentration = asset.getMarketValueCny()
                    .divide(wealth.getTotalAssets(), 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            if (concentration.compareTo(new BigDecimal("35")) > 0) {
                warnings.add(warning("CONCENTRATION_HIGH", "WARNING",
                        "该资产约占总资产 " + concentration.setScale(1, RoundingMode.HALF_UP) + "%"));
            }
        }
        if (profile != null && profile.getSavingsGoalAmount() != null && wealth.isInitialized()
                && wealth.getTotalAssets().compareTo(profile.getSavingsGoalAmount()) < 0) {
            warnings.add(warning("SAVINGS_GOAL", "INFO", "当前总资产尚未达到已设置的储蓄目标"));
        }
        if (profile != null && "CONSERVATIVE".equals(profile.getRiskPreference())
                && technicalScore.compareTo(new BigDecimal("65")) >= 0) {
            warnings.add(warning("RISK_PREFERENCE", "INFO", "技术条件较好，但你的风险偏好偏保守，请保持分批和仓位纪律"));
        }
        if (profile != null && "AGGRESSIVE".equals(profile.getRiskPreference())
                && technicalScore.compareTo(new BigDecimal("45")) < 0) {
            warnings.add(warning("RISK_PREFERENCE", "WARNING", "风险偏好偏进取不会改变当前技术偏弱的结论"));
        }
        warnings.add(warning("REFERENCE_ONLY", "INFO", "结果仅作分析参考，不连接券商，也不会自动交易"));
        return warnings;
    }

    private static Map<String, Object> warning(String code, String severity, String message) {
        return Map.of("code", code, "severity", severity, "message", message,
                "affectsTechnicalAnalysis", false);
    }

    private Map<String, Object> aiExplanation(InvestmentAnalysisSnapshot snapshot,
                                              Map<String, Object> technical,
                                              Map<String, Object> fundamental,
                                              InvestmentProduct product) {
        if (snapshot != null && snapshot.getAiExplanation() != null && !snapshot.getAiExplanation().isBlank()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "READY");
            result.put("text", snapshot.getAiExplanation());
            result.put("updatedAt", snapshot.getAiUpdatedAt());
            return result;
        }
        String verdict = String.valueOf(technical.getOrDefault("verdict", technical.getOrDefault("action", "WAIT")));
        String text = product.getName() + "当前结论为“" + verdict + "”。请结合页面中的周期分歧、支撑压力和风险警告分批判断。";
        return Map.of("status", "PENDING", "text", text,
                "cooldownMinutes", 30, "isRuleFallback", true);
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

    static int requiredHistoryDays(ResolvedHorizonProfile profile, int maxHistoryTradingDays) {
        long indicatorHistory = Math.max(20L, (long) profile.requiredHistoryDays() * 3L);
        return (int) Math.min(Math.max(1, maxHistoryTradingDays), indicatorHistory);
    }

    static int calendarLookbackDays(int tradingDays) {
        if (tradingDays < 1) {
            throw new IllegalArgumentException("交易日数量必须为正整数");
        }
        long calendarDays = (long) Math.ceil(tradingDays * 365.0 / 240.0) + 30L;
        return (int) Math.min(Integer.MAX_VALUE, calendarDays);
    }

    static boolean shouldRefreshQuotes(int quoteCount, int requiredHistoryDays, boolean refreshQuotes) {
        return quoteCount < requiredHistoryDays || refreshQuotes;
    }

}
