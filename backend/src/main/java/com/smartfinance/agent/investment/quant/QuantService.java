package com.smartfinance.agent.investment.quant;

import java.util.List;
import java.util.Map;

public interface QuantService {
    Map<String, Object> latestAnalysis(Long userId, Long assetId, String horizonCode);
    Map<String, Object> refresh(Long userId, Long assetId, String horizonCode);
    Map<String, Object> refreshExperiment(Long userId,
                                          Long assetId,
                                          String horizonCode,
                                          String experimentFingerprint,
                                          String modelFamily,
                                          Map<String, Object> parameters);
    Map<String, Object> refreshExperiment(Long userId,
                                          Long assetId,
                                          String horizonCode,
                                          String experimentFingerprint,
                                          String modelFamily,
                                          String algorithm,
                                          Map<String, Object> parameters);
    Map<String, Object> refreshExperiment(Long userId,
                                          Long assetId,
                                          Long universeId,
                                          String horizonCode,
                                          String experimentFingerprint,
                                          String modelFamily,
                                          String algorithm,
                                          Map<String, Object> parameters);
    String researchContextVersion(Long userId, Long assetId, Long universeId);
    Map<String, Object> job(Long userId, String jobId);
    Map<String, Object> cancelJob(Long userId, String jobId);
    void activatePaperModel(Long userId, String modelVersion);
    void activateAssetModel(Long userId, Long assetId, String modelVersion);
    Map<String, Object> modelManagement(Long userId, Long assetId, String horizonCode);
    List<Map<String, Object>> models(Long userId, Long assetId);
    Map<String, Object> actionPlan(Long userId, Long assetId, String horizonCode);
    Map<String, Object> strategyStatus(Long userId);
    Map<String, Object> paperAccount(Long userId);
}
