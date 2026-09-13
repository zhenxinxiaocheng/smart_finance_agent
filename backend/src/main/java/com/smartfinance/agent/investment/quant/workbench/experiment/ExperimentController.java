package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentProductDtos.*;

@RestController
@RequestMapping("/api/quant/v2/experiments")
public class ExperimentController {
    private final ExperimentProductService service;

    public ExperimentController(ExperimentProductService service) {
        this.service = service;
    }

    @PostMapping
    public Result<ExperimentListItemResponse> create(
            @RequestAttribute Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateRequest request) {
        return Result.success(service.create(userId, request, idempotencyKey));
    }

    @GetMapping
    public Result<?> list(
            @RequestAttribute Long userId,
            @RequestParam(required = false) String strategyId,
            @RequestParam(required = false) String sourceBacktestId,
            @RequestParam(required = false) String status) {
        return Result.success(service.list(userId, strategyId, sourceBacktestId, status));
    }

    @GetMapping("/eligibility")
    public Result<EligibilityResponse> eligibility(
            @RequestAttribute Long userId, @RequestParam String sourceBacktestId) {
        return Result.success(service.eligibility(userId, sourceBacktestId));
    }

    @GetMapping("/{id}")
    public Result<ExperimentDetailResponse> detail(
            @RequestAttribute Long userId, @PathVariable String id) {
        return Result.success(service.detail(userId, id));
    }
}
