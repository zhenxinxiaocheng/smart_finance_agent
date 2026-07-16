package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonProfileResponse;

public interface InvestmentHorizonService {
    ResolvedHorizonProfile resolve(Long userId, Long assetId);
    HorizonProfileResponse global(Long userId);
    HorizonProfileResponse saveGlobal(Long userId, HorizonProfileRequest request);
    HorizonProfileResponse saveAssetOverride(Long userId, Long assetId, HorizonProfileRequest request);
    HorizonProfileResponse clearAssetOverride(Long userId, Long assetId);
}
