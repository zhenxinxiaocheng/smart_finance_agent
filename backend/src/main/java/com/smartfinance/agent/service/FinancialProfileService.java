package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.FinancialProfileRequest;
import com.smartfinance.agent.entity.FinancialProfile;

public interface FinancialProfileService {

    FinancialProfile get(Long userId);

    FinancialProfile save(Long userId, FinancialProfileRequest request);

    String buildAgentContext(Long userId);
}
