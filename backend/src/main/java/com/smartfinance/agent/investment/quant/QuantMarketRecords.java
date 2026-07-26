package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.entity.ProductDailyQuote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QuantMarketRecords {
    private QuantMarketRecords() {
    }

    static List<Map<String, Object>> fromQuotes(List<ProductDailyQuote> quotes,
                                                String productType) {
        return quotes.stream().map(quote -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("data_date", quote.getTradeDate().toString());
            if ("MUTUAL_FUND".equals(productType)) {
                item.put("nav", quote.getClosePrice());
            } else {
                item.put("open", quote.getOpenPrice());
                item.put("high", quote.getHighPrice());
                item.put("low", quote.getLowPrice());
                item.put("close", quote.getClosePrice());
                item.put("volume", quote.getVolume());
            }
            return item;
        }).toList();
    }
}
