package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class QuantTrainingOrchestrator {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final InvestmentAssetMapper assetMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentDataQualitySnapshotMapper qualityMapper;
    private final InvestmentHorizonService horizonService;
    private final AnalysisServiceClient analysisClient;
    private final QuantBenchmarkProfileService benchmarkProfileService;
    private final QuantJobMapper jobMapper;
    private final QuantExperimentMapper experimentMapper;
    private final QuantResearchUniverseMapper universeMapper;
    private final QuantUniverseMembershipMapper membershipMapper;
    private final WealthService wealthService;
    private final QuantModelRegistryService modelRegistryService;
    private final ObjectMapper objectMapper;

    public QuantTrainingOrchestrator(InvestmentAssetMapper assetMapper,
                                     InvestmentProductMapper productMapper,
                                     ProductDailyQuoteMapper quoteMapper,
                                     InvestmentDataQualitySnapshotMapper qualityMapper,
                                     InvestmentHorizonService horizonService,
                                     AnalysisServiceClient analysisClient,
                                     QuantBenchmarkProfileService benchmarkProfileService,
                                     QuantJobMapper jobMapper,
                                     QuantExperimentMapper experimentMapper,
                                     QuantResearchUniverseMapper universeMapper,
                                     QuantUniverseMembershipMapper membershipMapper,
                                     WealthService wealthService,
                                     QuantModelRegistryService modelRegistryService,
                                     ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.qualityMapper = qualityMapper;
        this.horizonService = horizonService;
        this.analysisClient = analysisClient;
        this.benchmarkProfileService = benchmarkProfileService;
        this.jobMapper = jobMapper;
        this.experimentMapper = experimentMapper;
        this.universeMapper = universeMapper;
        this.membershipMapper = membershipMapper;
        this.wealthService = wealthService;
        this.modelRegistryService = modelRegistryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> refresh(Long userId, Long assetId, String horizonCode) {
        return refreshInternal(
                userId, assetId, null, horizonCode, null, null, "VALIDATED_ENSEMBLE", Map.of());
    }

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
        String jobType = experimentFingerprint == null ? "AUTO_SEARCH" : "TRAIN_PREDICT";
        request.put("type", jobType);
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
        job.setJobType(jobType);
        job.setStatus(String.valueOf(remote.getOrDefault("status", "QUEUED")));
        job.setDatasetVersion(quality.getDatasetVersion());
        job.setQuantConfigVersion(text(remote.get("configVersion")));
        job.setProductType(product.getProductType());
        job.setMetricsJson(writeJson(Map.of()));
        job.setHorizonProfileVersion(profile.version());
        job.setHorizonCode(horizon.code());
        job.setHorizonDays(horizon.targetHoldingDays());
        job.setExperimentFingerprint(experimentFingerprint);
        jobMapper.insert(job);
        return jobView(job);
    }

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
            Object metrics = result.get("backtestSummary");
            job.setMetricsJson(writeJson(metrics == null ? Map.of() : metrics));
        }
        if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) job.setFinishedAt(LocalDateTime.now());
        jobMapper.updateById(job);
        if ("SUCCEEDED".equals(status) && rawResult instanceof Map<?, ?> result) {
            modelRegistryService.persistArtifacts(job, castMap(result));
        }
        syncExperiment(job, rawResult);
        return jobView(job);
    }

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
        syncExperiment(job, null);
        return jobView(job);
    }

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
        job.setJobType(experimentFingerprint == null ? "AUTO_SEARCH" : "TRAIN_PREDICT");
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

    private void updateJobVersions(QuantJob job, Map<?, ?> result) {
        job.setFeatureSetVersion(text(result.get("featureSetVersion")));
        String configVersion = text(result.get("quantConfigVersion"));
        if (configVersion != null) job.setQuantConfigVersion(configVersion);
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
            String modelStatus = text(result.get("modelStatus"));
            if (modelStatus != null
                    && List.of("VALIDATED", "PAPER_VERIFIED").contains(modelStatus)) {
                experiment.setBestModelVersion(job.getModelVersion());
            }
            Object summary = result.get("backtestSummary");
            experiment.setSearchSummaryJson(writeJson(summary == null ? Map.of() : summary));
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

    private static String normalizeHorizon(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("分析周期不能为空");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultModelFamily(String productType) {
        return "STOCK".equalsIgnoreCase(productType) ? "A_SHARE_STOCK" : "ACTIVE_FUND";
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
