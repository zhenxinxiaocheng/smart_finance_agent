package com.smartfinance.agent.investment.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "investment.cache.detail")
public class InvestmentDetailCacheProperties {
    private boolean enabled = true;
    private Duration freshTtl = Duration.ofSeconds(30);
    private Duration staleTtl = Duration.ofMinutes(10);
    private Duration failureBackoff = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        if (freshTtl == null || freshTtl.isNegative() || freshTtl.isZero()
                || staleTtl == null || staleTtl.compareTo(freshTtl) < 0
                || failureBackoff == null || failureBackoff.isNegative() || failureBackoff.isZero()) {
            throw new IllegalArgumentException("Detail cache requires 0 < fresh-ttl <= stale-ttl and positive failure-backoff");
        }
    }
}
