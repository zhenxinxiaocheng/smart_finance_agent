package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import com.smartfinance.agent.investment.marketdata.contract.MarketDataContract;
import com.smartfinance.agent.investment.marketdata.collection.MarketDataCollectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductDailyQuoteService {
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final MarketDataCollectionRepository collections;

    public ProductDailyQuoteService(ProductDailyQuoteMapper quoteMapper, InvestmentRuntimeProperties runtimeProperties, MarketDataCollectionRepository collections) {
        this.quoteMapper = quoteMapper;
        this.runtimeProperties = runtimeProperties;
        this.collections = collections;
    }

    @Transactional
    public ProductDailyQuote persistBatch(InvestmentProduct product,Map<String,Object> response,String adjustment,LocalDate start,LocalDate end) {
        var stored=persistDailyQuotes(product,response,adjustment);
        collections.record(product.getId(),"PRICE",adjustment,start,end,response);
        return stored;
    }

    @Transactional
    public void recordEmpty(InvestmentProduct product,String adjustment,LocalDate start,LocalDate end,Map<String,Object> response) {
        collections.record(product.getId(),"PRICE",adjustment,start,end,response);
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
        InvestmentHistoryPreparationService.recordDates(response, adjustType);
        String provider = responseMetadata(response, "provider");
        String adapterVersion = responseMetadata(response, "adapterVersion");
        String contractVersion=optionalMetadata(response,"dataContractVersion");
        if(contractVersion!=null)MarketDataContract.forProduct("PRICE",product.getProductType()).validate(response,records);
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
        BigDecimal previousClose = previous == null || contractVersion!=null&&!contractVersion.equals(previous.getDataContractVersion())
                ? null : previous.getClosePrice();
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
            var old=existing.get(day);
            if(contractVersion!=null&&old!=null&&!contractVersion.equals(old.getDataContractVersion()))old=null;
            latest = prepareQuote(old, product, record, provider, adapterVersion, adjustType, previousClose);
            latest.setDataContractVersion(contractVersion);
            existing.put(day, latest);
            prepared.add(latest);
            previousClose = latest.getClosePrice();
        }
        if (latest == null) throw new IllegalStateException("未返回行情记录");
        int batchSize = runtimeProperties.getSync().getQuoteBatchSize();
        if (batchSize < 1 || batchSize > 2000) throw new IllegalStateException("行情写入批量配置无效");
        if(contractVersion!=null)quoteMapper.deleteIncompatible(product.getId(),adjustType,firstDate,lastDate,contractVersion);
        for (int start = 0; start < prepared.size(); start += batchSize) {
            quoteMapper.upsertHistory(prepared.subList(start, Math.min(start + batchSize, prepared.size())));
        }
        Map<LocalDate, ProductDailyQuote> stored = new HashMap<>();
        for (var quote : quoteMapper.selectSeries(List.of(product.getId()), firstDate, lastDate, java.util.Set.of(adjustType)))
            stored.put(quote.getTradeDate(), quote);
        for (var expected : prepared) {
            var actual = stored.get(expected.getTradeDate());
            if (actual == null || !same(expected.getClosePrice(), actual.getClosePrice(), 10)
                    || !same(expected.getOpenPrice(), actual.getOpenPrice(), 10)
                    || !same(expected.getHighPrice(), actual.getHighPrice(), 10)
                    || !same(expected.getLowPrice(), actual.getLowPrice(), 10)
                    || !same(expected.getTotalReturnIndex(), actual.getTotalReturnIndex(), 10)
                    || !same(expected.getVolume(), actual.getVolume(), 8)
                    || !same(expected.getAmount(), actual.getAmount(), 8)
                    || !same(expected.getTurnoverRate(), actual.getTurnoverRate(), 8)
                    || !java.util.Objects.equals(expected.getSource(), actual.getSource())
                    || !java.util.Objects.equals(expected.getAdapterVersion(), actual.getAdapterVersion())
                    || !java.util.Objects.equals(expected.getDataContractVersion(), actual.getDataContractVersion()))
                throw new IllegalStateException("行情保存核对失败：" + expected.getTradeDate());
        }
        return stored.get(lastDate);
    }

    static boolean same(BigDecimal expected, BigDecimal actual, int scale) {
        return expected == null ? actual == null : actual != null
                && expected.setScale(scale, RoundingMode.HALF_UP).compareTo(actual.setScale(scale, RoundingMode.HALF_UP)) == 0;
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
        if (decimal(record.get("open")) != null) quote.setOpenPrice(decimal(record.get("open")));
        if (decimal(record.get("high")) != null) quote.setHighPrice(decimal(record.get("high")));
        if (decimal(record.get("low")) != null) quote.setLowPrice(decimal(record.get("low")));
        Object closeValue = record.get("close");
        if (closeValue == null) closeValue = record.get("nav");
        quote.setClosePrice(decimal(closeValue));
        BigDecimal returnIndex = fund(product) ? totalReturnIndex(record) : decimal(record.get("total_return_index"));
        // 已有有效累计收益指数时不要用空值覆盖，避免历史分析被动失效。
        if (returnIndex != null && returnIndex.signum() > 0) {
            quote.setTotalReturnIndex(returnIndex);
        }
        BigDecimal effectivePrevious = decimal(record.get("previous_close"));
        if(effectivePrevious==null)effectivePrevious = previousClose == null ? quote.getPreviousClose() : previousClose;
        quote.setPreviousClose(effectivePrevious);
        if (effectivePrevious != null && quote.getClosePrice() != null) {
            BigDecimal changeAmount = quote.getClosePrice().subtract(effectivePrevious);
            quote.setChangeAmount(changeAmount);
            quote.setChangePercent(effectivePrevious.signum()>0 && quote.getClosePrice().signum()>0
                    ? changeAmount.divide(effectivePrevious, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100")) : null);
        }
        if (decimal(record.get("volume")) != null) quote.setVolume(decimal(record.get("volume")));
        if (decimal(record.get("amount")) != null) quote.setAmount(decimal(record.get("amount")));
        if (decimal(record.get("turnover_rate")) != null) quote.setTurnoverRate(decimal(record.get("turnover_rate")));
        quote.setSource(record.get("provider") == null ? provider : String.valueOf(record.get("provider")));
        quote.setAdapterVersion(record.get("adapter_version") == null ? adapterVersion : String.valueOf(record.get("adapter_version")));
        quote.setSyncedAt(LocalDateTime.now());
        return quote;
    }

    /** Both stored and snapshot fund returns use the same NAV reinvestment basis. */
    static BigDecimal totalReturnIndex(Map<?, ?> record) {
        BigDecimal direct = decimal(record.get("total_return_index"));
        if (direct != null) return direct;
        BigDecimal nav = decimal(record.get("close") == null ? record.get("nav") : record.get("close"));
        BigDecimal factor = decimal(record.get("adjustment_factor"));
        return nav == null || factor == null ? null : nav.multiply(factor);
    }

    private static boolean fund(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType())
                || "FUND".equals(product.getProductType());
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? null : new BigDecimal(String.valueOf(value));
    }

    static String responseMetadata(Map<String, Object> response, String key) {
        String value=optionalMetadata(response,key);
        if(value==null)throw new IllegalStateException("行情响应缺少 " + key);
        return value;
    }

    private static String optionalMetadata(Map<String,Object> response,String key) {
        Object direct = response.get(key);
        if (direct != null) return String.valueOf(direct);
        Object manifest = response.get("manifest");
        if (manifest instanceof Map<?, ?> map && map.get(key) != null) {
            return String.valueOf(map.get(key));
        }
        return null;
    }
}
