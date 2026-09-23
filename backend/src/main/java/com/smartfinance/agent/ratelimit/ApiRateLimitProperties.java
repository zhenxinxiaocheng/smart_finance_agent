package com.smartfinance.agent.ratelimit;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties("api.rate-limit")
public class ApiRateLimitProperties {
    private boolean enabled = true;
    private Duration failureBackoff = Duration.ofSeconds(30);
    private int localMaxEntries = 10000;
    private Rule login = new Rule(20, Duration.ofMinutes(1));
    private Rule chat = new Rule(10, Duration.ofMinutes(1));
    private Rule quoteRefresh = new Rule(6, Duration.ofMinutes(1));

    @Data
    public static class Rule {
        private int limit;
        private Duration window;
        public Rule() {}
        public Rule(int limit, Duration window) { this.limit = limit; this.window = window; }
    }

    public Rule rule(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN -> login;
            case CHAT -> chat;
            case QUOTE_REFRESH -> quoteRefresh;
        };
    }

    @PostConstruct
    public void validate() {
        if (localMaxEntries <= 0 || failureBackoff == null || failureBackoff.toMillis() <= 0)
            throw new IllegalArgumentException("Invalid rate limit fallback configuration");
        for (RateLimitScope scope : RateLimitScope.values()) {
            Rule rule = rule(scope);
            if (rule == null || rule.getLimit() <= 0 || rule.getWindow() == null || rule.getWindow().toMillis() <= 0)
                throw new IllegalArgumentException("Invalid rate limit rule: " + scope);
        }
    }
}
