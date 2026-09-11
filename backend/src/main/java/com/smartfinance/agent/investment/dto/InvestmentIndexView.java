package com.smartfinance.agent.investment.dto;

import java.math.BigDecimal;

public record InvestmentIndexView(
        Long id,
        String indexCode,
        String name,
        String market,
        boolean defaultItem,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal changeAmount,
        BigDecimal previousClose,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        String dataTime,
        String fetchedAt,
        String syncStatus,
        String syncError
) {
}
