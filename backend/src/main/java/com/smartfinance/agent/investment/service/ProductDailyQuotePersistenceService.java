package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class ProductDailyQuotePersistenceService {
    private final ProductDailyQuoteMapper quoteMapper;

    public ProductDailyQuotePersistenceService(ProductDailyQuoteMapper quoteMapper) {
        this.quoteMapper = quoteMapper;
    }

    @SuppressWarnings("unchecked")
    public ProductDailyQuote persistHistory(InvestmentProduct product, Map<String, Object> response,
                                            String adjustType) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");
        if (records == null || records.isEmpty()) throw new IllegalStateException("未返回行情记录");
        String provider = responseMetadata(response, "provider");
        String adapterVersion = responseMetadata(response, "adapterVersion");
        List<Map<String, Object>> ordered = records.stream()
                .sorted(Comparator.comparing(row -> String.valueOf(row.get("data_date")))).toList();
        ProductDailyQuote latest = null;
        BigDecimal previousClose = null;
        for (Map<String, Object> row : ordered) {
            LocalDate date = LocalDate.parse(String.valueOf(row.get("data_date")).substring(0, 10));
            ProductDailyQuote quote = existing(product.getId(), date, adjustType);
            if (quote == null) {
                quote = new ProductDailyQuote();
                quote.setProductId(product.getId());
                quote.setTradeDate(date);
                quote.setAdjustType(adjustType);
            }
            quote.setOpenPrice(decimal(row.get("open")));
            quote.setHighPrice(decimal(row.get("high")));
            quote.setLowPrice(decimal(row.get("low")));
            Object close = row.get("close") == null ? row.get("nav") : row.get("close");
            quote.setClosePrice(decimal(close));
            BigDecimal tri = decimal(row.get("total_return_index"));
            BigDecimal factor = decimal(row.get("adjustment_factor"));
            if (tri == null && fund(product) && factor != null && quote.getClosePrice() != null)
                tri = quote.getClosePrice().multiply(factor);
            if (quote.getTotalReturnIndex() == null || quote.getTotalReturnIndex().signum() <= 0)
                quote.setTotalReturnIndex(tri);
            BigDecimal effectivePrevious = previousClose == null ? quote.getPreviousClose() : previousClose;
            quote.setPreviousClose(effectivePrevious);
            if (effectivePrevious != null && effectivePrevious.signum() != 0
                    && quote.getClosePrice() != null) {
                BigDecimal change = quote.getClosePrice().subtract(effectivePrevious);
                quote.setChangeAmount(change);
                quote.setChangePercent(change.divide(effectivePrevious, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")));
            }
            quote.setVolume(decimal(row.get("volume")));
            quote.setSource(provider);
            quote.setAdapterVersion(adapterVersion);
            quote.setSyncedAt(LocalDateTime.now());
            save(quote);
            latest = quote;
            previousClose = quote.getClosePrice();
        }
        return latest;
    }

    public boolean persistDisplay(ProductDailyQuote incoming) {
        ProductDailyQuote quote = existing(incoming.getProductId(), incoming.getTradeDate(), "NONE");
        if (quote != null && quote.getTotalReturnIndex() != null
                && quote.getTotalReturnIndex().signum() > 0) return false;
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
        save(quote);
        return true;
    }

    private ProductDailyQuote existing(Long productId, LocalDate date, String adjustType) {
        return quoteMapper.selectOne(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)
                .eq(ProductDailyQuote::getTradeDate, date)
                .eq(ProductDailyQuote::getAdjustType, adjustType));
    }

    private void save(ProductDailyQuote quote) {
        if (quote.getId() == null) quoteMapper.insert(quote);
        else quoteMapper.updateById(quote);
    }

    private static boolean fund(InvestmentProduct product) {
        return "MUTUAL_FUND".equals(product.getProductType()) || "FUND".equals(product.getProductType());
    }

    private static BigDecimal decimal(Object value) {
        return value == null || "null".equals(String.valueOf(value))
                ? null : new BigDecimal(String.valueOf(value));
    }

    private static String responseMetadata(Map<String, Object> response, String key) {
        Object direct = response.get(key);
        if (direct != null) return String.valueOf(direct);
        if (response.get("manifest") instanceof Map<?, ?> manifest && manifest.get(key) != null)
            return String.valueOf(manifest.get(key));
        throw new IllegalStateException("行情响应缺少 " + key);
    }
}
