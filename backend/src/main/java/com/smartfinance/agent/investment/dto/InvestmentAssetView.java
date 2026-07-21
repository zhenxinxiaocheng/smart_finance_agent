package com.smartfinance.agent.investment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class InvestmentAssetView {
    private Long id;
    private Long accountId;
    private Long productId;
    private String productType;
    private String code;
    private String name;
    private String market;
    private String currency;
    private BigDecimal quantity;
    private BigDecimal averageCost;
    private BigDecimal latestPrice;
    private BigDecimal previousClose;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal volume;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal volumeRatio;
    private BigDecimal amplitude;
    private BigDecimal marketValueCny;
    private BigDecimal unrealizedPnlCny;
    private BigDecimal holdingReturnPercent;
    private LocalDate dataDate;
    private LocalDateTime fetchedAt;
    private String note;
    private String syncStatus;
    private String syncError;
    private LocalDateTime updatedAt;
}
