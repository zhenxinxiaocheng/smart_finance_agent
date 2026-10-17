package com.smartfinance.agent.investment.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "investment.cache.quote")
public class InvestmentQuoteCacheProperties {
    private boolean enabled = true;
    private Duration ttl = Duration.ofSeconds(30);
    private Duration indexFreshTtl = Duration.ofSeconds(30);
    private Duration indexStaleTtl = Duration.ofMinutes(10);
    private Duration failureBackoff = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        if (ttl == null || ttl.toMillis() <= 0
                || indexFreshTtl == null || indexFreshTtl.toMillis() <= 0
                || indexStaleTtl == null || indexStaleTtl.compareTo(indexFreshTtl) < 0
                || failureBackoff == null || failureBackoff.toMillis() <= 0) {
            throw new IllegalArgumentException("Quote cache durations must be positive and index fresh TTL <= stale TTL");
        }
    }
}
