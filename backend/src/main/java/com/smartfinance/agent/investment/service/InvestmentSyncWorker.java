package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.*;
import com.smartfinance.agent.investment.mapper.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 行情同步与持久化的唯一入口。
 *
 * <p>个人持仓同步（{@link #processPending()}）与 Market Data 全市场同步
 * （{@link MarketDataSyncService}）共用 {@link #persistDailyQuotes}，
 * 不再各自维护一套落库逻辑。
 */
@Component
public class InvestmentSyncWorker {

    private final InvestmentSyncBatchMapper syncBatchMapper;
    private final InvestmentPositionMapper positionMapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final DailyExchangeRateMapper exchangeRateMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentDataQualityService dataQualityService;
    private final InvestmentDataJobService dataJobService;
    private final InvestmentHorizonService horizonService;
    private final InvestmentHorizonProperties horizonProperties;
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
                                InvestmentDataQualityService dataQualityService,
                                InvestmentDataJobService dataJobService,
                                InvestmentHorizonService horizonService,
                                InvestmentHorizonProperties horizonProperties,
                                InvestmentRuntimeProperties runtimeProperties) {
        this.syncBatchMapper = syncBatchMapper;
        this.positionMapper = positionMapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.exchangeRateMapper = exchangeRateMapper;
        this.analysisClient = analysisClient;
        this.dataQualityService = dataQualityService;
        this.dataJobService = dataJobService;
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
                // Holdings use executable prices; research history is maintained by the shared history job.
                Map<String, Object> rawResponse;
                if (InvestmentHistoryPreparationService.supportsQuality(product)) {
                    InvestmentDataQualityService.Evaluation quality = dataQualityService.resolve(
                            product, startDate, endDate, "NONE", true);
                    if (quality.blocked()) {
                        throw new IllegalStateException("数据质量门禁未通过：" + quality.datasetVersion());
                    }
                    dataQualityService.claim(quality);
                    rawResponse = quality.response();
                } else {
                    rawResponse = analysisClient.marketDailyQuotes(product, startDate, endDate, "NONE");
                }
                provider = responseMetadata(rawResponse, "provider");
                LocalDate priceDate = InvestmentHistoryPreparationService.recordDates(rawResponse).stream()
                        .max(LocalDate::compareTo).orElseThrow(() -> new IllegalStateException("未返回行情记录"));
                if (fund(product) && InvestmentHistoryPreparationService.hasInvalidFundReturns(rawResponse)) {
                    throw new IllegalStateException("基金历史缺少有效累计收益");
                }
                BigDecimal fxRate = "CNY".equals(product.getCurrency()) ? BigDecimal.ONE
                        : latestFxRate(product.getCurrency(), priceDate);
                Runnable persist = () -> {
                    ProductDailyQuote latest = persistDailyQuotes(product, rawResponse, "NONE");
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
        batch.setStatus(failed == 0 ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL");
        batch.setFinishedAt(LocalDateTime.now());
        syncBatchMapper.updateById(batch);
    }

    int requiredHistoryDays(Long userId) {
        return InvestmentAnalysisServiceImpl.requiredHistoryDays(
                horizonService.resolve(userId, null), horizonProperties);
    }

    /**
     * 把数据源返回的日线记录写入 product_daily_quote。
     * 已存在的日期只更新字段，不重复插入。
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public ProductDailyQuote persistDailyQuotes(InvestmentProduct product, Map<String, Object> response,
                                         String adjustType) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("未返回行情记录");
        }
        String provider = responseMetadata(response, "provider");
        String adapterVersion = responseMetadata(response, "adapterVersion");
        List<Map<String, Object>> orderedRecords = records.stream()
                .sorted(Comparator.comparing(record -> String.valueOf(record.get("data_date"))))
                .toList();
        ProductDailyQuote latest = null;
        LocalDate firstDate = LocalDate.parse(String.valueOf(orderedRecords.get(0).get("data_date")).substring(0, 10));
        ProductDailyQuote previous = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, adjustType)
                .lt(ProductDailyQuote::getTradeDate, firstDate)
                .orderByDesc(ProductDailyQuote::getTradeDate).last("LIMIT 1"));
        BigDecimal previousClose = previous == null ? null : previous.getClosePrice();
        LocalDate lastDate = LocalDate.parse(String.valueOf(orderedRecords.get(orderedRecords.size() - 1)
                .get("data_date")).substring(0, 10));
        Map<LocalDate, ProductDailyQuote> existing = new HashMap<>();
        for (ProductDailyQuote quote : quoteMapper.selectList(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, adjustType)
                .between(ProductDailyQuote::getTradeDate, firstDate, lastDate))) {
            existing.put(quote.getTradeDate(), quote);
        }
        List<ProductDailyQuote> prepared = new ArrayList<>(orderedRecords.size());
        for (Map<String, Object> record : orderedRecords) {
            LocalDate day = LocalDate.parse(String.valueOf(record.get("data_date")).substring(0, 10));
            latest = prepareQuote(existing.get(day), product, record, provider, adapterVersion, adjustType, previousClose);
            existing.put(day, latest);
            prepared.add(latest);
            previousClose = latest.getClosePrice();
        }
        if (latest == null) throw new IllegalStateException("未返回行情记录");
        int batchSize = runtimeProperties.getSync().getQuoteBatchSize();
        if (batchSize < 1 || batchSize > 2000) throw new IllegalStateException("行情写入批量配置无效");
        for (int start = 0; start < prepared.size(); start += batchSize) {
            quoteMapper.upsertHistory(prepared.subList(start, Math.min(start + batchSize, prepared.size())));
        }
        ProductDailyQuote stored = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, product.getId())
                .eq(ProductDailyQuote::getAdjustType, adjustType).eq(ProductDailyQuote::getTradeDate, lastDate));
        return stored == null ? latest : stored;
    }

    /**
     * 保存展示口径的当日行情（实时快照），不覆盖已有有效累计收益指数。
     */
    @Transactional
    public boolean persistDisplayQuote(InvestmentProduct product, ProductDailyQuote incoming) {
        if (product == null || product.getId() == null
                || !product.getId().equals(incoming.getProductId())
                || incoming.getTradeDate() == null || incoming.getClosePrice() == null
                || incoming.getClosePrice().signum() <= 0) {
            throw new IllegalArgumentException("展示行情缺少有效产品、日期或价格");
        }
        ProductDailyQuote quote = quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, incoming.getProductId())
                .eq(ProductDailyQuote::getTradeDate, incoming.getTradeDate())
                .eq(ProductDailyQuote::getAdjustType, "NONE"));
        if (quote != null && quote.getTotalReturnIndex() != null
                && quote.getTotalReturnIndex().signum() > 0) {
            return false;
        }
        if (quote == null) {
            quote = incoming;
            quote.setAdjustType("NONE");
        } else {
            quote.setClosePrice(incoming.getClosePrice());
            quote.setPreviousClose(incoming.getPreviousClose());
            quote.setChangeAmount(incoming.getChangeAmount());
            quote.setChangePercent(incoming.getChangePercent());
            quote.setOpenPrice(incoming.getOpenPrice());
            quote.setHighPrice(incoming.getHighPrice());
            quote.setLowPrice(incoming.getLowPrice());
            quote.setVolume(incoming.getVolume());
            quote.setAmount(incoming.getAmount());
            quote.setTurnoverRate(incoming.getTurnoverRate());
            quote.setVolumeRatio(incoming.getVolumeRatio());
            quote.setAmplitude(incoming.getAmplitude());
            quote.setSource(incoming.getSource());
            quote.setAdapterVersion(incoming.getAdapterVersion());
            quote.setSyncedAt(incoming.getSyncedAt());
        }
        if (quote.getId() == null) quoteMapper.insert(quote);
        else quoteMapper.updateById(quote);
        // 最新净值只负责展示；累计收益仍由同一历史任务从最早缺失日期补齐。
        if (fund(product)) dataJobService.queueAnalysisForProduct(product.getId());
        return true;
    }

    private ProductDailyQuote prepareQuote(ProductDailyQuote quote, InvestmentProduct product, Map<String, Object> record,
                                        String provider, String adapterVersion, String adjustType,
                                        BigDecimal previousClose) {
        LocalDate tradeDate = LocalDate.parse(String.valueOf(record.get("data_date")).substring(0, 10));
        if (quote == null) {
            quote = new ProductDailyQuote();
            quote.setProductId(product.getId());
            quote.setTradeDate(tradeDate);
            quote.setAdjustType(adjustType);
        }
        quote.setOpenPrice(decimal(record.get("open")));
        quote.setHighPrice(decimal(record.get("high")));
        quote.setLowPrice(decimal(record.get("low")));
        Object closeValue = record.get("close");
        if (closeValue == null) closeValue = record.get("nav");
        quote.setClosePrice(decimal(closeValue));
        BigDecimal returnIndex = decimal(record.get("total_return_index"));
        BigDecimal factor = decimal(record.get("adjustment_factor"));
        if (returnIndex == null && fund(product) && factor != null && quote.getClosePrice() != null) {
            returnIndex = quote.getClosePrice().multiply(factor);
        }
        // 已有有效累计收益指数时不要用空值覆盖，避免历史分析被动失效。
        if (returnIndex != null && returnIndex.signum() > 0) {
            quote.setTotalReturnIndex(returnIndex);
        }
        BigDecimal effectivePrevious = previousClose == null ? quote.getPreviousClose() : previousClose;
        quote.setPreviousClose(effectivePrevious);
        if (effectivePrevious != null && effectivePrevious.signum() != 0 && quote.getClosePrice() != null) {
            BigDecimal changeAmount = quote.getClosePrice().subtract(effectivePrevious);
            quote.setChangeAmount(changeAmount);
            quote.setChangePercent(changeAmount.divide(effectivePrevious, 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")));
        }
        quote.setVolume(decimal(record.get("volume")));
        quote.setSource(provider);
        quote.setAdapterVersion(adapterVersion);
        quote.setSyncedAt(LocalDateTime.now());
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

    private static boolean fund(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType())
                || "FUND".equals(product.getProductType());
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? null : new BigDecimal(String.valueOf(value));
    }

    private static String responseMetadata(Map<String, Object> response, String key) {
        Object direct = response.get(key);
        if (direct != null) return String.valueOf(direct);
        Object manifest = response.get("manifest");
        if (manifest instanceof Map<?, ?> map && map.get(key) != null) {
            return String.valueOf(map.get(key));
        }
        throw new IllegalStateException("行情响应缺少 " + key);
    }
}
