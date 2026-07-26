package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.dto.InvestmentAssetCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetUpdateRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InvestmentAssetService {
    AnalysisServiceClient.ResolvedProduct resolve(Long userId, String productType, String code);
    InvestmentAssetView create(Long userId, InvestmentAssetCreateRequest request);
    List<InvestmentAssetView> list(Long userId);
    InvestmentAssetView get(Long userId, Long assetId);
    InvestmentAssetView update(Long userId, Long assetId, InvestmentAssetUpdateRequest request);
    void delete(Long userId, Long assetId);
    InvestmentAssetView sync(Long userId, Long assetId);
    InvestmentAssetView refresh(Long userId, Long assetId, boolean force);
    List<InvestmentAssetView> refreshAll(Long userId, boolean force);
    InvestmentAssetView applyRecurringInvestment(Long userId, Long accountId, Long productId,
                                                 BigDecimal amount, BigDecimal price,
                                                 Long planId, LocalDate tradeDate);
}
