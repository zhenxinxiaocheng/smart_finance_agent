package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;

public interface InvestmentAnalysisService {
    InvestmentAssetDetailResponse detail(Long userId, Long assetId);
    InvestmentAssetDetailResponse updatePreference(Long userId, Long assetId, HorizonProfileRequest request);
    InvestmentAssetDetailResponse clearPreference(Long userId, Long assetId);
    InvestmentAssetDetailResponse refresh(Long userId, Long assetId);
    InvestmentAssetDetailResponse retryData(Long userId, Long assetId);
}
