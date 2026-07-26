package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quant")
public class QuantModelManagementController {
    private final QuantService quantService;

    public QuantModelManagementController(QuantService quantService) {
        this.quantService = quantService;
    }

    @PostMapping("/assets/{assetId}/training-sessions")
    public Result<Map<String, Object>> startTraining(
            @RequestAttribute Long userId,
            @PathVariable Long assetId,
            @Valid @RequestBody TrainingRequest request
    ) {
        return Result.success(quantService.refresh(userId, assetId, request.horizonCode()));
    }

    @GetMapping("/training-sessions/{sessionId}")
    public Result<Map<String, Object>> trainingSession(
            @RequestAttribute Long userId,
            @PathVariable String sessionId
    ) {
        return Result.success(quantService.job(userId, sessionId));
    }

    @GetMapping("/assets/{assetId}/model-management")
    public Result<Map<String, Object>> modelManagement(
            @RequestAttribute Long userId,
            @PathVariable Long assetId
    ) {
        return Result.success(quantService.modelManagement(userId, assetId));
    }

    @GetMapping("/assets/{assetId}/action-plan")
    public Result<Map<String, Object>> actionPlan(
            @RequestAttribute Long userId,
            @PathVariable Long assetId,
            @RequestParam String horizonCode
    ) {
        return Result.success(quantService.actionPlan(userId, assetId, horizonCode));
    }

    @GetMapping("/assets/{assetId}/models")
    public Result<List<Map<String, Object>>> models(
            @RequestAttribute Long userId,
            @PathVariable Long assetId
    ) {
        return Result.success(quantService.models(userId, assetId));
    }

    @PostMapping("/assets/{assetId}/models/{modelVersion}/activate")
    public Result<Void> activate(
            @RequestAttribute Long userId,
            @PathVariable Long assetId,
            @PathVariable String modelVersion
    ) {
        quantService.activateAssetModel(userId, assetId, modelVersion);
        return Result.success();
    }

    public record TrainingRequest(@NotBlank String horizonCode) {
    }
}
