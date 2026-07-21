package com.smartfinance.agent.wealth.service;

import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;

import java.math.BigDecimal;

public interface WealthService {
    WealthOverviewResponse overview(Long userId);
    WealthOverviewResponse setBaseline(Long userId, BigDecimal cashBalance);
}
