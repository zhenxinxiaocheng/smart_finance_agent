package com.smartfinance.agent.investment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix="market-data.full-library")
public class MarketDataScopeProperties {
    private boolean enabled;
    private Set<String> markets=Set.of("CN_A","CN_ETF","US","INDEX","FUND_CN");
    private Set<String> datasets=Set.of("PRICE","PROFILE","FINANCIALS","CORPORATE_ACTIONS","FUND_HOLDINGS",
            "FUND_OPERATIONS","VALUATION","SHAREHOLDERS","FUND_NOTICES");
    public boolean allows(String market,String dataset) {
        return enabled&&market!=null&&markets.contains(market)&&datasets.contains(dataset);
    }
}
