package com.smartfinance.agent.investment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class InvestmentOverviewResponse {
    private BigDecimal totalAssetCny = BigDecimal.ZERO;
    private BigDecimal netInvestmentCny = BigDecimal.ZERO;
    private BigDecimal totalPnlCny = BigDecimal.ZERO;
    private BigDecimal dailyChangeCny = BigDecimal.ZERO;
    private LocalDate dataDate;
    private String syncStatus = "NOT_SYNCED";
    private List<InvestmentPositionView> positions = List.of();
    private List<Map<String, Object>> cashBalances = List.of();
}
