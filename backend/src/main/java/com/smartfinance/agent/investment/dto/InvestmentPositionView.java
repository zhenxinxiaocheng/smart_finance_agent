package com.smartfinance.agent.investment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvestmentPositionView {
    private Long id;
    private Long accountId;
    private String accountName;
    private Long productId;
    private String productType;
    private String market;
    private String code;
    private String name;
    private String currency;
    private BigDecimal quantity;
    private BigDecimal costAmount;
    private BigDecimal averageCost;
    private BigDecimal realizedPnl;
    private BigDecimal latestPrice;
    private BigDecimal marketValueCny;
    private BigDecimal unrealizedPnlCny;
    private LocalDate dataDate;
}
