package com.smartfinance.agent.investment.quant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "investment.quant.paper")
public class QuantPaperProperties {
    private BigDecimal initialCashCny;
    private BigDecimal minimumOrderCny;
    private int stockLotSize;
    private int fundQuantityScale;
    private BigDecimal feeBps;
    private BigDecimal slippageBps;
    private BigDecimal maximumVolumeParticipation;
    private BigDecimal limitLockMinimumMoveRatio;
    private int stockExecutionDelayDays;
    private int fundExecutionDelayDays;
    private int qdiiExecutionDelayDays;
    private int accountOrderHistoryLimit;
}
