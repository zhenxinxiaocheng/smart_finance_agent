package com.smartfinance.agent.investment.quant;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class QuantServiceImpl implements QuantService {
    private final QuantPredictionQueryService predictionQueryService;
    private final QuantTrainingOrchestrator trainingOrchestrator;
    private final QuantModelRegistryService modelRegistryService;
    private final QuantTradingDecisionService tradingDecisionService;
    private final QuantActionPlanService actionPlanService;
    private final QuantModelManagementService modelManagementService;

    public QuantServiceImpl(QuantPredictionQueryService predictionQueryService,
                            QuantTrainingOrchestrator trainingOrchestrator,
                            QuantModelRegistryService modelRegistryService,
                            QuantTradingDecisionService tradingDecisionService,
                            QuantActionPlanService actionPlanService,
                            QuantModelManagementService modelManagementService) {
        this.predictionQueryService = predictionQueryService;
        this.trainingOrchestrator = trainingOrchestrator;
        this.modelRegistryService = modelRegistryService;
        this.tradingDecisionService = tradingDecisionService;
        this.actionPlanService = actionPlanService;
        this.modelManagementService = modelManagementService;
    }

    @Override
    public Map<String, Object> latestAnalysis(Long userId, Long assetId, String horizonCode) {
        return predictionQueryService.latestAnalysis(userId, assetId, horizonCode);
    }

    @Override
    public Map<String, Object> refresh(Long userId, Long assetId, String horizonCode) {
        return trainingOrchestrator.refresh(userId, assetId, horizonCode);
    }

    @Override
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 Map<String, Object> parameters) {
        return trainingOrchestrator.refreshExperiment(
                userId,
                assetId,
                horizonCode,
                experimentFingerprint,
                modelFamily,
                parameters
        );
    }

    @Override
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 String algorithm,
                                                 Map<String, Object> parameters) {
        return trainingOrchestrator.refreshExperiment(
                userId,
                assetId,
                horizonCode,
                experimentFingerprint,
                modelFamily,
                algorithm,
                parameters
        );
    }

    @Override
    public Map<String, Object> refreshExperiment(Long userId,
                                                 Long assetId,
                                                 Long universeId,
                                                 String horizonCode,
                                                 String experimentFingerprint,
                                                 String modelFamily,
                                                 String algorithm,
                                                 Map<String, Object> parameters) {
        return trainingOrchestrator.refreshExperiment(
                userId,
                assetId,
                universeId,
                horizonCode,
                experimentFingerprint,
                modelFamily,
                algorithm,
                parameters
        );
    }

    @Override
    public String researchContextVersion(Long userId, Long assetId, Long universeId) {
        return trainingOrchestrator.researchContextVersion(userId, assetId, universeId);
    }

    @Override
    public Map<String, Object> job(Long userId, String jobId) {
        return trainingOrchestrator.job(userId, jobId);
    }

    @Override
    public Map<String, Object> cancelJob(Long userId, String jobId) {
        return trainingOrchestrator.cancelJob(userId, jobId);
    }

    @Override
    public void activatePaperModel(Long userId, String modelVersion) {
        modelRegistryService.activatePaperModel(userId, modelVersion);
    }

    @Override
    public void activateAssetModel(Long userId, Long assetId, String modelVersion) {
        modelRegistryService.activatePaperModel(userId, assetId, modelVersion);
    }

    @Override
    public Map<String, Object> modelManagement(Long userId,
                                               Long assetId,
                                               String horizonCode) {
        return modelManagementService.management(userId, assetId, horizonCode);
    }

    @Override
    public List<Map<String, Object>> models(Long userId, Long assetId) {
        return modelManagementService.models(userId, assetId);
    }

    @Override
    public Map<String, Object> actionPlan(Long userId, Long assetId, String horizonCode) {
        return actionPlanService.actionPlan(userId, assetId, horizonCode);
    }

    @Override
    public Map<String, Object> strategyStatus(Long userId) {
        return modelRegistryService.strategyStatus(userId);
    }

    @Override
    public Map<String, Object> paperAccount(Long userId) {
        return tradingDecisionService.paperAccount(userId);
    }
}
