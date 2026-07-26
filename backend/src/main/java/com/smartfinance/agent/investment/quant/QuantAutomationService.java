package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QuantAutomationService {
    private final InvestmentHorizonService horizonService;
    private final QuantInferenceOrchestrator inferenceOrchestrator;
    private final QuantTrainingOrchestrator trainingOrchestrator;

    public QuantAutomationService(InvestmentHorizonService horizonService,
                                  QuantInferenceOrchestrator inferenceOrchestrator,
                                  QuantTrainingOrchestrator trainingOrchestrator) {
        this.horizonService = horizonService;
        this.inferenceOrchestrator = inferenceOrchestrator;
        this.trainingOrchestrator = trainingOrchestrator;
    }

    public Map<String, Object> onDataReady(Long userId, Long assetId) {
        ResolvedHorizonProfile profile = horizonService.resolve(userId, assetId);
        List<Map<String, Object>> updates = new ArrayList<>();
        for (HorizonSetting horizon : profile.settings()) {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("horizonCode", horizon.code());
            try {
                update.put(
                        "prediction",
                        inferenceOrchestrator.refresh(userId, assetId, horizon.code())
                );
            } catch (RuntimeException exception) {
                update.put("prediction", failure("PREDICTION_FAILED", exception));
            }
            try {
                update.put(
                        "training",
                        trainingOrchestrator.refresh(userId, assetId, horizon.code())
                );
            } catch (RuntimeException exception) {
                update.put("training", failure("TRAINING_FAILED", exception));
            }
            updates.add(update);
        }
        return Map.of(
                "status", "SCHEDULED",
                "assetId", assetId,
                "updates", updates
        );
    }

    private static Map<String, Object> failure(String code, RuntimeException exception) {
        String message = exception.getMessage();
        return Map.of(
                "status", "FAILED",
                "errorCode", code,
                "userMessage", message == null || message.isBlank()
                        ? "量化自动更新暂未完成"
                        : message
        );
    }
}
