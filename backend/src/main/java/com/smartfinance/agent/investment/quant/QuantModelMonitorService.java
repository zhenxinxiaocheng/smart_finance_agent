package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.service.AnalysisServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class QuantModelMonitorService {
    private static final Logger log = LoggerFactory.getLogger(QuantModelMonitorService.class);
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final QuantStrategyVersionMapper strategyMapper;
    private final QuantModelVersionMapper modelMapper;
    private final QuantPredictionMapper predictionMapper;
    private final QuantModelMonitorMapper monitorMapper;
    private final QuantPaperOrderMapper orderMapper;
    private final QuantPaperFillMapper fillMapper;
    private final InvestmentAssetMapper assetMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final AnalysisServiceClient analysisServiceClient;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final int strategyBatchLimit;

    public QuantModelMonitorService(QuantStrategyVersionMapper strategyMapper,
                                    QuantModelVersionMapper modelMapper,
                                    QuantPredictionMapper predictionMapper,
                                    QuantModelMonitorMapper monitorMapper,
                                    QuantPaperOrderMapper orderMapper,
                                    QuantPaperFillMapper fillMapper,
                                    InvestmentAssetMapper assetMapper,
                                    ProductDailyQuoteMapper quoteMapper,
                                    AnalysisServiceClient analysisServiceClient,
                                    InvestmentRuntimeProperties runtimeProperties,
                                    ObjectMapper objectMapper,
                                    @Value("${investment.quant.monitor.strategy-batch-limit}") int strategyBatchLimit) {
        this.strategyMapper = strategyMapper;
        this.modelMapper = modelMapper;
        this.predictionMapper = predictionMapper;
        this.monitorMapper = monitorMapper;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.assetMapper = assetMapper;
        this.quoteMapper = quoteMapper;
        this.analysisServiceClient = analysisServiceClient;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
        this.strategyBatchLimit = strategyBatchLimit;
    }

    @Scheduled(
            initialDelayString = "${investment.quant.monitor.initial-delay-ms}",
            fixedDelayString = "${investment.quant.monitor.poll-delay-ms}"
    )
    public void monitorActiveStrategies() {
        List<QuantStrategyVersion> strategies = strategyMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .in(QuantStrategyVersion::getStatus, "PAPER", "CHAMPION", "CHALLENGER")
                        .orderByAsc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT " + strategyBatchLimit));
        for (QuantStrategyVersion strategy : strategies) {
            try {
                monitor(strategy);
            } catch (RuntimeException exception) {
                log.warn("Quant model monitoring deferred: strategyVersion={}", strategy.getStrategyVersion());
            }
        }
    }

    @Transactional
    public void monitor(QuantStrategyVersion strategy) {
        QuantModelVersion model = modelMapper.selectOne(new LambdaQueryWrapper<QuantModelVersion>()
                .eq(QuantModelVersion::getModelVersion, strategy.getModelVersion())
                .last("LIMIT 1"));
        if (model == null) return;
        LocalDate monitoredOn = LocalDate.now(runtimeProperties.getMarket().getZone());
        if (monitorMapper.selectCount(new LambdaQueryWrapper<QuantModelMonitor>()
                .eq(QuantModelMonitor::getModelVersion, model.getModelVersion())
                .eq(QuantModelMonitor::getMonitoredOn, monitoredOn)) > 0) return;
        List<Observation> observations = maturedObservations(model.getModelVersion());
        if (observations.isEmpty()) return;
        Map<String, Object> policy = readJson(model.getMetricsJson());
        BigDecimal meanBrier = mean(observations.stream().map(Observation::brier).toList());
        BigDecimal meanLogLoss = mean(observations.stream().map(Observation::logLoss).toList());
        BigDecimal meanExcess = mean(observations.stream().map(Observation::realizedExcess).toList());
        BigDecimal maximumBrier = decimal(policy, "policyMaximumBrierScore");
        BigDecimal minimumExcess = decimal(policy, "policyMinimumRealizedExcessReturn");
        DriftAssessment featureDrift = featureDrift(
                policy, observations.get(observations.size() - 1).featureVector());
        DriftAssessment dataDrift = dataDrift(policy, meanExcess);
        boolean failed = meanBrier.compareTo(maximumBrier) > 0
                || meanExcess.compareTo(minimumExcess) < 0
                || featureDrift.failed()
                || dataDrift.failed();
        QuantModelMonitor monitor = new QuantModelMonitor();
        monitor.setModelVersion(model.getModelVersion());
        monitor.setMonitoredOn(monitoredOn);
        monitor.setBrierScore(meanBrier);
        monitor.setRealizedExcessReturn(meanExcess);
        monitor.setDriftStatus(failed ? "FAIL" : "PASS");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("observationCount", observations.size());
        evidence.put("latestMaturityDate",
                observations.get(observations.size() - 1).maturityDate());
        evidence.put("benchmarkCodes", observations.stream()
                .map(Observation::benchmarkCode)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        evidence.put("meanLogLoss", meanLogLoss);
        evidence.put("featureDriftStatus", featureDrift.status());
        evidence.put("featureMaximumZScore", featureDrift.score());
        evidence.put("dataDriftStatus", dataDrift.status());
        evidence.put("dataZScore", dataDrift.score());
        monitor.setEvidenceJson(writeJson(evidence));
        monitorMapper.insert(monitor);
        applyLifecycle(strategy, model, policy, monitor, observations.size());
    }

    private List<Observation> maturedObservations(String modelVersion) {
        List<QuantPrediction> predictions = predictionMapper.selectList(
                new LambdaQueryWrapper<QuantPrediction>()
                        .eq(QuantPrediction::getModelVersion, modelVersion)
                        .isNotNull(QuantPrediction::getProbabilityPositiveExcess)
                        .orderByAsc(QuantPrediction::getAsOfDate));
        List<Observation> result = new ArrayList<>();
        LocalDate lastMaturityDate = null;
        for (QuantPrediction prediction : predictions) {
            if (prediction.getBenchmarkCode() == null || prediction.getBenchmarkCode().isBlank()) continue;
            if (lastMaturityDate != null && !prediction.getAsOfDate().isAfter(lastMaturityDate)) continue;
            InvestmentAsset asset = assetMapper.selectById(prediction.getAssetId());
            if (asset == null) continue;
            List<ProductDailyQuote> quotes = quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                    .eq(ProductDailyQuote::getProductId, asset.getProductId())
                    .ge(ProductDailyQuote::getTradeDate, prediction.getAsOfDate())
                    .orderByAsc(ProductDailyQuote::getTradeDate));
            int endIndex = prediction.getHorizonDays();
            if (quotes.size() <= endIndex || quotes.get(0).getClosePrice() == null
                    || quotes.get(endIndex).getClosePrice() == null) continue;
            BigDecimal grossReturn = quotes.get(endIndex).getClosePrice()
                    .divide(quotes.get(0).getClosePrice(), 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            BigDecimal benchmarkReturn = benchmarkReturn(
                    prediction.getBenchmarkCode(),
                    prediction.getAsOfDate(),
                    quotes.get(endIndex).getTradeDate()
            );
            BigDecimal cost = prediction.getRoundTripCostBps() == null ? BigDecimal.ZERO
                    : prediction.getRoundTripCostBps().divide(BPS, 12, RoundingMode.HALF_UP);
            BigDecimal excess = grossReturn.subtract(benchmarkReturn).subtract(cost);
            BigDecimal actual = excess.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            BigDecimal probabilityError = prediction.getProbabilityPositiveExcess().subtract(actual);
            BigDecimal logLoss = logLoss(prediction.getProbabilityPositiveExcess(), actual);
            lastMaturityDate = quotes.get(endIndex).getTradeDate();
            result.add(new Observation(
                    probabilityError.multiply(probabilityError),
                    logLoss,
                    excess,
                    lastMaturityDate,
                    prediction.getBenchmarkCode(),
                    readJson(prediction.getFeatureVectorJson())
            ));
        }
        return result;
    }

    private void applyLifecycle(QuantStrategyVersion strategy,
                                QuantModelVersion model,
                                Map<String, Object> policy,
                                QuantModelMonitor latest,
                                int observationCount) {
        int failureLimit = decimal(policy, "policyConsecutiveFailuresBeforeRetirement").intValueExact();
        if (consecutiveFailures(strategy.getModelVersion(), failureLimit) >= failureLimit) {
            strategy.setStatus("RETIRED");
            strategy.setRetiredAt(LocalDateTime.now());
            strategyMapper.updateById(strategy);
            model.setStatus("RETIRED");
            modelMapper.updateById(model);
            promoteFallback(strategy);
            return;
        }
        if (!"PAPER".equals(strategy.getStatus()) || !"PASS".equals(latest.getDriftStatus())) return;
        int requiredDays = Math.max(
                60,
                Math.max(
                        decimal(policy, "policyMinimumPaperTradingDays").intValueExact(),
                        3 * model.getHorizonDays()
                )
        );
        long tradingDays = fillMapper.countTradingDays(strategy.getStrategyVersion());
        long rejectedOrders = orderMapper.selectCount(new LambdaQueryWrapper<QuantPaperOrder>()
                .eq(QuantPaperOrder::getStrategyVersion, strategy.getStrategyVersion())
                .eq(QuantPaperOrder::getStatus, "REJECTED"));
        if (tradingDays < requiredDays || observationCount < 3 || rejectedOrders > 0) return;
        List<QuantStrategyVersion> champions = strategyMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, strategy.getUserId())
                        .eq(QuantStrategyVersion::getAssetId, strategy.getAssetId())
                        .eq(QuantStrategyVersion::getProductType, strategy.getProductType())
                        .eq(QuantStrategyVersion::getModelFamily, strategy.getModelFamily())
                        .eq(QuantStrategyVersion::getHorizonCode, strategy.getHorizonCode())
                        .eq(QuantStrategyVersion::getDeploymentRole, "CHAMPION")
                        .eq(QuantStrategyVersion::getStatus, "CHAMPION"));
        for (QuantStrategyVersion champion : champions) {
            if (!sameDeploymentSlot(strategy, champion)) continue;
            champion.setDeploymentRole("ARCHIVED");
            champion.setStatus("ARCHIVED");
            strategyMapper.updateById(champion);
        }
        strategy.setDeploymentRole("CHAMPION");
        strategy.setStatus("CHAMPION");
        strategyMapper.updateById(strategy);
        model.setStatus("PAPER_VERIFIED");
        modelMapper.updateById(model);
    }

    private BigDecimal benchmarkReturn(String benchmarkCode, LocalDate start, LocalDate end) {
        Map<String, Object> response = analysisServiceClient.benchmarkHistory(benchmarkCode, start, end);
        Object rawRecords = response.get("records");
        if (!(rawRecords instanceof List<?> records) || records.size() < 2) {
            throw new IllegalStateException("官方基准在监控区间内无可用行情");
        }
        BigDecimal first = recordClose(records.get(0));
        BigDecimal last = recordClose(records.get(records.size() - 1));
        if (first.signum() <= 0) throw new IllegalStateException("官方基准起始值无效");
        return last.divide(first, 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
    }

    private static BigDecimal recordClose(Object value) {
        if (!(value instanceof Map<?, ?> record) || record.get("close") == null) {
            throw new IllegalStateException("官方基准行情字段缺失");
        }
        return new BigDecimal(String.valueOf(record.get("close")));
    }

    private static BigDecimal logLoss(BigDecimal probability, BigDecimal actual) {
        double clipped = Math.max(1e-12, Math.min(1 - 1e-12, probability.doubleValue()));
        double outcome = actual.doubleValue();
        return BigDecimal.valueOf(
                -(outcome * Math.log(clipped) + (1 - outcome) * Math.log(1 - clipped))
        );
    }

    private int consecutiveFailures(String modelVersion, int maximum) {
        List<QuantModelMonitor> monitors = monitorMapper.selectList(new LambdaQueryWrapper<QuantModelMonitor>()
                .eq(QuantModelMonitor::getModelVersion, modelVersion)
                .orderByDesc(QuantModelMonitor::getMonitoredOn)
                .last("LIMIT " + maximum));
        int count = 0;
        for (QuantModelMonitor monitor : monitors) {
            if (!"FAIL".equals(monitor.getDriftStatus())) break;
            count++;
        }
        return count;
    }

    private void promoteFallback(QuantStrategyVersion retired) {
        if (!"CHAMPION".equals(retired.getDeploymentRole())) return;
        QuantStrategyVersion fallback = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getUserId, retired.getUserId())
                        .eq(QuantStrategyVersion::getAssetId, retired.getAssetId())
                        .eq(QuantStrategyVersion::getProductType, retired.getProductType())
                        .eq(QuantStrategyVersion::getModelFamily, retired.getModelFamily())
                        .eq(QuantStrategyVersion::getHorizonCode, retired.getHorizonCode())
                        .eq(QuantStrategyVersion::getDeploymentRole, "ARCHIVED")
                        .eq(QuantStrategyVersion::getStatus, "ARCHIVED")
                        .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT 1"));
        if (fallback == null) return;
        QuantModelVersion fallbackModel = modelMapper.selectOne(
                new LambdaQueryWrapper<QuantModelVersion>()
                        .eq(QuantModelVersion::getModelVersion, fallback.getModelVersion())
                        .eq(QuantModelVersion::getStatus, "PAPER_VERIFIED")
                        .last("LIMIT 1")
        );
        if (fallbackModel == null || !sameDeploymentSlot(retired, fallback)) return;
        fallback.setDeploymentRole("CHAMPION");
        fallback.setStatus("CHAMPION");
        strategyMapper.updateById(fallback);
    }

    private static boolean sameDeploymentSlot(QuantStrategyVersion left,
                                              QuantStrategyVersion right) {
        return Objects.equals(left.getUserId(), right.getUserId())
                && Objects.equals(left.getAssetId(), right.getAssetId())
                && Objects.equals(left.getProductType(), right.getProductType())
                && Objects.equals(left.getModelFamily(), right.getModelFamily())
                && Objects.equals(left.getHorizonCode(), right.getHorizonCode());
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型监控证据保存失败", exception);
        }
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) throw new IllegalStateException("模型版本缺少监控策略：" + key);
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
    }

    private DriftAssessment featureDrift(
            Map<String, Object> policy,
            Map<String, Object> currentFeatures
    ) {
        Object rawDistribution = policy.get("featureDistribution");
        BigDecimal threshold = optionalDecimal(policy, "policyMaximumFeatureZScore");
        if (!(rawDistribution instanceof Map<?, ?> distribution)
                || currentFeatures.isEmpty()
                || threshold == null) {
            return DriftAssessment.notEvaluated();
        }
        BigDecimal maximum = BigDecimal.ZERO;
        int evaluated = 0;
        for (Map.Entry<String, Object> current : currentFeatures.entrySet()) {
            Object rawStats = distribution.get(current.getKey());
            if (!(rawStats instanceof Map<?, ?> stats)) continue;
            BigDecimal mean = optionalDecimal(stats, "mean");
            BigDecimal std = optionalDecimal(stats, "std");
            BigDecimal value = number(current.getValue());
            if (mean == null || std == null || value == null || std.abs().compareTo(
                    new BigDecimal("0.000000000001")) <= 0) continue;
            BigDecimal zScore = value.subtract(mean).abs()
                    .divide(std.abs(), 10, RoundingMode.HALF_UP);
            maximum = maximum.max(zScore);
            evaluated++;
        }
        return evaluated == 0
                ? DriftAssessment.notEvaluated()
                : DriftAssessment.evaluated(maximum, threshold);
    }

    private static DriftAssessment dataDrift(
            Map<String, Object> policy,
            BigDecimal realizedExcess
    ) {
        Object rawDistribution = policy.get("labelDistribution");
        BigDecimal threshold = optionalDecimal(policy, "policyMaximumLabelZScore");
        if (!(rawDistribution instanceof Map<?, ?> distribution) || threshold == null) {
            return DriftAssessment.notEvaluated();
        }
        BigDecimal mean = optionalDecimal(distribution, "mean");
        BigDecimal std = optionalDecimal(distribution, "std");
        if (mean == null || std == null || std.abs().compareTo(
                new BigDecimal("0.000000000001")) <= 0) {
            return DriftAssessment.notEvaluated();
        }
        BigDecimal zScore = realizedExcess.subtract(mean).abs()
                .divide(std.abs(), 10, RoundingMode.HALF_UP);
        return DriftAssessment.evaluated(zScore, threshold);
    }

    private static BigDecimal optionalDecimal(Map<?, ?> values, String key) {
        return number(values.get(key));
    }

    private static BigDecimal number(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record Observation(BigDecimal brier,
                               BigDecimal logLoss,
                               BigDecimal realizedExcess,
                               LocalDate maturityDate,
                               String benchmarkCode,
                               Map<String, Object> featureVector) {
    }

    private record DriftAssessment(String status, BigDecimal score) {
        static DriftAssessment notEvaluated() {
            return new DriftAssessment("NOT_EVALUATED", null);
        }

        static DriftAssessment evaluated(BigDecimal score, BigDecimal threshold) {
            return new DriftAssessment(
                    score.compareTo(threshold) > 0 ? "FAIL" : "PASS",
                    score
            );
        }

        boolean failed() {
            return "FAIL".equals(status);
        }
    }
}
