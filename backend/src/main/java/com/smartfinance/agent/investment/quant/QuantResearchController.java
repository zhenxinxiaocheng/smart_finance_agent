package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quant")
public class QuantResearchController {
    private final QuantResearchService service;

    public QuantResearchController(QuantResearchService service) {
        this.service = service;
    }

    @GetMapping("/model-families")
    public Result<List<Map<String, Object>>> modelFamilies() {
        return Result.success(service.modelFamilies());
    }

    @GetMapping("/parameter-schema")
    public Result<Map<String, Object>> parameterSchema() {
        return Result.success(service.parameterSchema());
    }

    @GetMapping("/benchmarks")
    public Result<List<Map<String, Object>>> benchmarks() {
        return Result.success(service.benchmarks());
    }

    @GetMapping("/research-universes")
    public Result<List<Map<String, Object>>> researchUniverses() {
        return Result.success(service.researchUniverses());
    }

    @PostMapping("/experiments")
    public Result<Map<String, Object>> createExperiment(
            @RequestAttribute Long userId,
            @Valid @RequestBody ExperimentRequest request
    ) {
        return Result.success(service.createExperiment(userId, request));
    }

    @GetMapping("/experiments")
    public Result<List<Map<String, Object>>> experiments(@RequestAttribute Long userId) {
        return Result.success(service.experiments(userId));
    }

    @GetMapping("/experiments/{id}")
    public Result<Map<String, Object>> experiment(
            @RequestAttribute Long userId,
            @PathVariable Long id
    ) {
        return Result.success(service.experiment(userId, id));
    }

    @PostMapping("/experiments/{id}/cancel")
    public Result<Map<String, Object>> cancelExperiment(
            @RequestAttribute Long userId,
            @PathVariable Long id
    ) {
        return Result.success(service.cancelExperiment(userId, id));
    }

    @PostMapping("/experiments/{id}/promote")
    public Result<Map<String, Object>> promoteExperiment(
            @RequestAttribute Long userId,
            @PathVariable Long id
    ) {
        return Result.success(service.promoteExperiment(userId, id));
    }

    @GetMapping("/data-quality")
    public Result<Map<String, Object>> dataQuality(@RequestAttribute Long userId) {
        return Result.success(service.dataQuality(userId));
    }

    @GetMapping("/paper-strategies/{id}")
    public Result<Map<String, Object>> paperStrategy(
            @RequestAttribute Long userId,
            @PathVariable Long id
    ) {
        return Result.success(service.paperStrategy(userId, id));
    }

    public record ExperimentRequest(
            @NotNull @Positive Long assetId,
            @Positive Long universeId,
            @NotBlank String modelFamily,
            @NotBlank String horizonCode,
            @NotNull Map<String, Object> parameters,
            @NotBlank String algorithm
    ) {
        public ExperimentRequest(Long assetId,
                                 String modelFamily,
                                 String horizonCode,
                                 Map<String, Object> parameters) {
            this(assetId, null, modelFamily, horizonCode, parameters, "REGIME_ENSEMBLE");
        }
    }
}
