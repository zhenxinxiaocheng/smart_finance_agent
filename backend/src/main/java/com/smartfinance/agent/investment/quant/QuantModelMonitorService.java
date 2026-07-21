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
        BigDecimal meanExcess = mean(observations.stream().map(Observation::realizedExcess).toList());
        BigDecimal maximumBrier = decimal(policy, "policyMaximumBrierScore");
        BigDecimal minimumExcess = decimal(policy, "policyMinimumRealizedExcessReturn");
        boolean failed = meanBrier.compareTo(maximumBrier) > 0 || meanExcess.compareTo(minimumExcess) < 0;
        QuantModelMonitor monitor = new QuantModelMonitor();
        monitor.setModelVersion(model.getModelVersion());
        monitor.setMonitoredOn(monitoredOn);
        monitor.setBrierScore(meanBrier);
        monitor.setRealizedExcessReturn(meanExcess);
        monitor.setDriftStatus(failed ? "FAIL" : "PASS");
        monitor.setEvidenceJson(writeJson(Map.of(
                "observationCount", observations.size(),
                "latestMaturityDate", observations.get(observations.size() - 1).maturityDate(),
                "benchmarkCode", "CASH_CNY")));
        monitorMapper.insert(monitor);
        applyLifecycle(strategy, policy, monitor);
    }

    private List<Observation> maturedObservations(String modelVersion) {
        List<QuantPrediction> predictions = predictionMapper.selectList(
                new LambdaQueryWrapper<QuantPrediction>()
                        .eq(QuantPrediction::getModelVersion, modelVersion)
                        .isNotNull(QuantPrediction::getProbabilityPositiveExcess)
                        .orderByAsc(QuantPrediction::getAsOfDate));
        List<Observation> result = new ArrayList<>();
        for (QuantPrediction prediction : predictions) {
            if (!"CASH_CNY".equals(prediction.getBenchmarkCode())) continue;
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
            BigDecimal cost = prediction.getRoundTripCostBps() == null ? BigDecimal.ZERO
                    : prediction.getRoundTripCostBps().divide(BPS, 12, RoundingMode.HALF_UP);
            BigDecimal excess = grossReturn.subtract(cost);
            BigDecimal actual = excess.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            BigDecimal probabilityError = prediction.getProbabilityPositiveExcess().subtract(actual);
            result.add(new Observation(probabilityError.multiply(probabilityError), excess,
                    quotes.get(endIndex).getTradeDate()));
        }
        return result;
    }

    private void applyLifecycle(QuantStrategyVersion strategy, Map<String, Object> policy,
                                QuantModelMonitor latest) {
        int failureLimit = decimal(policy, "policyConsecutiveFailuresBeforeRetirement").intValueExact();
        if (consecutiveFailures(strategy.getModelVersion(), failureLimit) >= failureLimit) {
            strategy.setStatus("RETIRED");
            strategy.setRetiredAt(LocalDateTime.now());
            strategyMapper.updateById(strategy);
            promoteFallback(strategy);
            return;
        }
        if (!"PAPER".equals(strategy.getStatus()) || !"PASS".equals(latest.getDriftStatus())) return;
        int requiredDays = decimal(policy, "policyMinimumPaperTradingDays").intValueExact();
        long tradingDays = fillMapper.countTradingDays(strategy.getStrategyVersion());
        long rejectedOrders = orderMapper.selectCount(new LambdaQueryWrapper<QuantPaperOrder>()
                .eq(QuantPaperOrder::getStrategyVersion, strategy.getStrategyVersion())
                .eq(QuantPaperOrder::getStatus, "REJECTED"));
        if (tradingDays < requiredDays || rejectedOrders > 0) return;
        List<QuantStrategyVersion> champions = strategyMapper.selectList(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getProductType, strategy.getProductType())
                        .eq(QuantStrategyVersion::getStatus, "CHAMPION"));
        for (QuantStrategyVersion champion : champions) {
            champion.setStatus("CHALLENGER");
            strategyMapper.updateById(champion);
        }
        strategy.setStatus("CHAMPION");
        strategyMapper.updateById(strategy);
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
        QuantStrategyVersion fallback = strategyMapper.selectOne(
                new LambdaQueryWrapper<QuantStrategyVersion>()
                        .eq(QuantStrategyVersion::getProductType, retired.getProductType())
                        .eq(QuantStrategyVersion::getStatus, "CHALLENGER")
                        .orderByDesc(QuantStrategyVersion::getUpdatedAt)
                        .last("LIMIT 1"));
        if (fallback == null) return;
        fallback.setStatus("CHAMPION");
        strategyMapper.updateById(fallback);
    }

    private Map<String, Object> readJson(String value) {
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

    private record Observation(BigDecimal brier, BigDecimal realizedExcess, LocalDate maturityDate) {
    }
}
