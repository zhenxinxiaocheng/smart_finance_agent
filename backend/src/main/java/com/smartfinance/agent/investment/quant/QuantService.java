package com.smartfinance.agent.investment.quant;

import java.util.Map;

public interface QuantService {
    Map<String, Object> latestAnalysis(Long userId, Long assetId, String horizonCode);
    Map<String, Object> refresh(Long userId, Long assetId, String horizonCode);
    Map<String, Object> job(Long userId, String jobId);
    Map<String, Object> strategyStatus(Long userId);
    Map<String, Object> paperAccount(Long userId);
}
