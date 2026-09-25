package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class QuoteSeriesPolicy {
    private final InvestmentRuntimeProperties runtime;

    public QuoteSeriesPolicy(InvestmentRuntimeProperties runtime) {
        this.runtime = runtime;
    }

    public String researchAdjustType(InvestmentProduct product) {
        return researchAdjustType(product.getProductType(), product.getMarket());
    }

    public String researchAdjustType(String productType, String market) {
        if (Set.of("MUTUAL_FUND", "FUND", "INDEX").contains(productType)) return "NONE";
        if ("ETF".equals(productType)
                && market != null && Set.of("NASDAQ", "NYSE", "AMEX").contains(market)) return "NONE";
        return runtime.getDataQuality().getStockAdjustType();
    }

    public List<String> marketAdjustments(InvestmentProduct product) {
        String research = researchAdjustType(product);
        return "NONE".equals(research) ? List.of("NONE") : List.of("NONE", research);
    }

    public String datasetType(InvestmentProduct product) {
        return Set.of("MUTUAL_FUND", "FUND").contains(product.getProductType()) ? "NAV" : "PRICE";
    }
}
