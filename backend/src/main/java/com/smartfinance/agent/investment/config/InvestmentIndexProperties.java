package com.smartfinance.agent.investment.config;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "investment.index-watchlist")
@PropertySource(value = "classpath:investment-indexes.properties", encoding = "UTF-8")
public class InvestmentIndexProperties {

    private int searchLimit;
    private List<DefaultIndex> defaults = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (searchLimit < 1 || searchLimit > 50) {
            throw new IllegalStateException("investment.index-watchlist.search-limit 必须在 1 到 50 之间");
        }
        if (defaults.isEmpty() || defaults.stream().anyMatch(DefaultIndex::incomplete)) {
            throw new IllegalStateException("investment.index-watchlist.defaults 配置不完整");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DefaultIndex {
        private String indexCode;
        private String displayName;
        private String market;

        private boolean incomplete() {
            return blank(indexCode) || blank(displayName) || blank(market);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
