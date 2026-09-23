package com.smartfinance.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties("finance.cache.statistics")
public class FinanceStatisticsCacheProperties {
    private boolean enabled = true;
    private Duration ttl = Duration.ofSeconds(60);
    private Duration failureBackoff = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        if (ttl == null || ttl.toMillis() <= 0 || failureBackoff == null || failureBackoff.toMillis() <= 0) {
            throw new IllegalArgumentException("Statistics cache durations must be positive");
        }
    }
}
