package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class InvestmentSyncWorker {

    private final InvestmentSyncBatchMapper syncBatchMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final UnifiedMarketDataIngestionService ingestion;
    private final DailyExchangeRateMapper exchangeRateMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentHorizonService horizonService;
    private final InvestmentHorizonProperties horizonProperties;
    private final InvestmentRuntimeProperties runtimeProperties;

    public InvestmentSyncWorker(InvestmentSyncBatchMapper syncBatchMapper,
                                InvestmentPositionMapper positionMapper,
                                InvestmentProductMapper productMapper,
                                ProductDailyQuoteMapper quoteMapper,
                                UnifiedMarketDataIngestionService ingestion,
                                DailyExchangeRateMapper exchangeRateMapper,
                                AnalysisServiceClient analysisClient,
                                InvestmentHorizonService horizonService,
                                InvestmentHorizonProperties horizonProperties,
                                InvestmentRuntimeProperties runtimeProperties) {
        this.syncBatchMapper = syncBatchMapper;
        this.positionMapper = positionMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.ingestion = ingestion;
        this.exchangeRateMapper = exchangeRateMapper;
        this.analysisClient = analysisClient;
        this.horizonService = horizonService;
        this.horizonProperties = horizonProperties;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(
            initialDelayString = "${investment.runtime.sync.initial-delay-ms}",
            fixedDelayString = "${investment.runtime.sync.poll-delay-ms}"
    )
    public void processPending() {
        List<InvestmentSyncBatch> batches = syncBatchMapper.selectList(new LambdaQueryWrapper<InvestmentSyncBatch>()
                .eq(InvestmentSyncBatch::getStatus, "PENDING").orderByAsc(InvestmentSyncBatch::getId)
                .last("LIMIT " + runtimeProperties.getSync().getBatchLimit()));
        for (InvestmentSyncBatch batch : batches) {
            process(batch);
        }
    }

    @Transactional
    void process(InvestmentSyncBatch batch) {
        batch.setStatus("RUNNING");
        syncBatchMapper.updateById(batch);
        int success = 0;
        int failed = 0;
        String provider = null;
        String lastError = null;
        LocalDate endDate = LocalDate.now(runtimeProperties.getMarket().getZone());
        LocalDate startDate = endDate.minusDays(
                InvestmentAnalysisServiceImpl.calendarLookbackDays(
                        requiredHistoryDays(batch.getUserId()), horizonProperties));
        List<InvestmentPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getUserId, batch.getUserId()).gt(InvestmentPosition::getQuantity, BigDecimal.ZERO));
        for (InvestmentPosition position : positions) {
            InvestmentProduct product = productMapper.selectById(position.getProductId());
            if (product == null) {
                failed++;
                continue;
            }
            try {
                String researchAdjust = "MUTUAL_FUND".equals(product.getProductType()) ? "NONE"
                        : runtimeProperties.getDataQuality().getStockAdjustType();
                UnifiedMarketDataIngestionService.Result result = ingestion.ingest(
                        product, startDate, endDate, researchAdjust, startDate);
                if (!"NONE".equals(researchAdjust))
                    ingestion.ingest(product, startDate, endDate, "NONE", startDate);
                provider = result.evaluation() == null ? null : result.evaluation().snapshot().getProvider();
                ProductDailyQuote latest = latestQuote(product, "NONE");
                BigDecimal close = latest.getClosePrice();
                position.setLatestPrice(close);
                position.setDataDate(latest.getTradeDate());
                BigDecimal fxRate = "CNY".equals(product.getCurrency()) ? BigDecimal.ONE
                        : latestFxRate(product.getCurrency(), position.getDataDate());
                position.setMarketValueCny(close.multiply(position.getQuantity()).multiply(fxRate));
                position.setUnrealizedPnlCny(position.getMarketValueCny().subtract(position.getCostAmount().multiply(fxRate)));
                positionMapper.updateById(position);
                success++;
            } catch (Exception ex) {
                failed++;
                lastError = ex.getMessage();
            }
        }
        batch.setProvider(provider);
        batch.setRowsSuccess(success);
        batch.setRowsFailed(failed);
        int maxErrorLength = runtimeProperties.getSync().getErrorMessageMaxLength();
        batch.setErrorMessage(lastError == null ? null
                : lastError.substring(0, Math.min(maxErrorLength, lastError.length())));
        batch.setStatus(failed == 0 ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL");
        batch.setFinishedAt(LocalDateTime.now());
        syncBatchMapper.updateById(batch);
    }

    int requiredHistoryDays(Long userId) {
        return InvestmentAnalysisServiceImpl.requiredHistoryDays(
                horizonService.resolve(userId, null), horizonProperties);
    }

    private ProductDailyQuote latestQuote(InvestmentProduct product, String adjustType) {
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, adjustType)
                .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
        if (quote == null) throw new IllegalStateException("未返回行情记录");
        return quote;
    }


    private BigDecimal latestFxRate(String currency, LocalDate dataDate) {
        Map<String, Object> response = analysisClient.dailyFx(currency,
                fxStartDate(dataDate), dataDate);
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");
        if (records == null || records.isEmpty()) throw new IllegalStateException("未返回 " + currency + "/CNY 汇率");
        Map<String, Object> latest = records.stream()
                .max(Comparator.comparing(item -> String.valueOf(item.get("data_date")))).orElseThrow();
        LocalDate rateDate = LocalDate.parse(String.valueOf(latest.get("data_date")));
        DailyExchangeRate rate = exchangeRateMapper.selectOne(new LambdaQueryWrapper<DailyExchangeRate>()
                .eq(DailyExchangeRate::getBaseCurrency, currency).eq(DailyExchangeRate::getQuoteCurrency, "CNY")
                .eq(DailyExchangeRate::getRateDate, rateDate));
        if (rate == null) {
            rate = new DailyExchangeRate();
            rate.setBaseCurrency(currency);
            rate.setQuoteCurrency("CNY");
            rate.setRateDate(rateDate);
        }
        rate.setRate(decimal(latest.get("rate")));
        rate.setSource(String.valueOf(response.get("provider")));
        rate.setAdapterVersion(String.valueOf(response.get("adapterVersion")));
        rate.setSyncedAt(LocalDateTime.now());
        if (rate.getId() == null) exchangeRateMapper.insert(rate); else exchangeRateMapper.updateById(rate);
        return rate.getRate();
    }

    LocalDate fxStartDate(LocalDate dataDate) {
        return dataDate.minusDays(runtimeProperties.getSync().getFxLookbackCalendarDays());
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? null : new BigDecimal(String.valueOf(value));
    }

}
