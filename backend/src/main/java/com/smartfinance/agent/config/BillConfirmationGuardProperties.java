package com.smartfinance.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Data
@Component
@ConfigurationProperties("bill.confirmation-guard")
public class BillConfirmationGuardProperties {
    private boolean enabled = true;
    private Duration leaseTtl = Duration.ofSeconds(30);
    private Duration failureBackoff = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        if (leaseTtl == null || leaseTtl.toMillis() <= 0 || failureBackoff == null || failureBackoff.toMillis() <= 0)
            throw new IllegalArgumentException("Bill confirmation guard durations must be positive");
    }
}
