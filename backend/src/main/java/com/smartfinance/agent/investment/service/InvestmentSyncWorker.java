package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 处理个人持仓同步批次及估值。 */
@Component
public class InvestmentSyncWorker {

    private final InvestmentSyncBatchMapper syncBatchMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final DailyExchangeRateMapper exchangeRateMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentHistoryPreparationService historyPreparation;
    private final InvestmentDataJobService dataJobService;
    private final InvestmentRuntimeProperties runtimeProperties;
    private TransactionTemplate writeTransaction;

    @Autowired
    void configureTransactions(PlatformTransactionManager manager) {
        writeTransaction = new TransactionTemplate(manager);
    }

    public InvestmentSyncWorker(InvestmentSyncBatchMapper syncBatchMapper,
                                InvestmentPositionMapper positionMapper,
                                InvestmentProductMapper productMapper,
                                ProductDailyQuoteMapper quoteMapper,
                                DailyExchangeRateMapper exchangeRateMapper,
                                AnalysisServiceClient analysisClient,
                                InvestmentHistoryPreparationService historyPreparation,
                                InvestmentDataJobService dataJobService,
                                InvestmentRuntimeProperties runtimeProperties) {
        this.syncBatchMapper = syncBatchMapper;
        this.positionMapper = positionMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.exchangeRateMapper = exchangeRateMapper;
        this.analysisClient = analysisClient;
        this.historyPreparation = historyPreparation;
        this.dataJobService = dataJobService;
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

    void process(InvestmentSyncBatch batch) {
        batch.setStatus("RUNNING");
        syncBatchMapper.updateById(batch);
        int success = 0;
        int failed = 0;
        String provider = null;
        String lastError = null;
        List<InvestmentPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<InvestmentPosition>()
                .eq(InvestmentPosition::getUserId, batch.getUserId()).gt(InvestmentPosition::getQuantity, BigDecimal.ZERO));
        for (InvestmentPosition position : positions) {
            InvestmentProduct product = productMapper.selectById(position.getProductId());
            if (product == null) {
                failed++;
                continue;
            }
            try {
                historyPreparation.prepareProduct(product, fund(product) ? "FUND_NAV_HISTORY" : "STOCK_HISTORY", false);
                ProductDailyQuote latest = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                        .eq(ProductDailyQuote::getProductId, product.getId()).eq(ProductDailyQuote::getAdjustType, "NONE")
                        .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
                if (latest == null) {
                    lastError = "本次查询暂无行情，暂不能更新估值";
                    continue;
                }
                provider = latest.getSource();
                BigDecimal fxRate = "CNY".equals(product.getCurrency()) ? BigDecimal.ONE
                        : latestFxRate(product.getCurrency(), latest.getTradeDate());
                Runnable persist = () -> {
                    BigDecimal close = latest.getClosePrice();
                    position.setLatestPrice(close);
                    position.setDataDate(latest.getTradeDate());
                    position.setMarketValueCny(close.multiply(position.getQuantity()).multiply(fxRate));
                    position.setUnrealizedPnlCny(position.getMarketValueCny()
                            .subtract(position.getCostAmount().multiply(fxRate)));
                    positionMapper.updateById(position);
                    dataJobService.queueAnalysisForProduct(product.getId());
                };
                if (writeTransaction == null) persist.run();
                else writeTransaction.executeWithoutResult(status -> persist.run());
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
        batch.setStatus(failed == 0 && success == positions.size() ? "SUCCESS"
                : failed > 0 && success == 0 ? "FAILED" : "PARTIAL");
        batch.setFinishedAt(LocalDateTime.now());
        syncBatchMapper.updateById(batch);
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

    private static boolean fund(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType())
                || "FUND".equals(product.getProductType());
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? null : new BigDecimal(String.valueOf(value));
    }

}
