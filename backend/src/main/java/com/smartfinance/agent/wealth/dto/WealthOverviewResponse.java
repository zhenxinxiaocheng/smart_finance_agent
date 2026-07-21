package com.smartfinance.agent.wealth.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class WealthOverviewResponse {
    private boolean initialized;
    private BigDecimal totalAssets;
    private BigDecimal dailyCash;
    private BigDecimal cashBaseline;
    private LocalDateTime cashBaselineAt;
    private BigDecimal investmentTotal = BigDecimal.ZERO;
    private BigDecimal investmentCash = BigDecimal.ZERO;
    private BigDecimal holdingMarketValue = BigDecimal.ZERO;
    private BigDecimal incomeAfterBaseline = BigDecimal.ZERO;
    private BigDecimal expenseAfterBaseline = BigDecimal.ZERO;
    private BigDecimal netInvestmentTransfer = BigDecimal.ZERO;
    private List<String> warnings = List.of();
}
