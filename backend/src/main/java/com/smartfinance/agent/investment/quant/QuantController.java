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

import java.util.Map;

@RestController
@RequestMapping("/api/investment")
public class QuantController {
    private final QuantService service;

    public QuantController(QuantService service) {
        this.service = service;
    }

    @GetMapping("/assets/{id}/quant-analysis")
    public Result<Map<String, Object>> analysis(@RequestAttribute Long userId,
                                                @PathVariable Long id,
                                                @RequestParam String horizonCode) {
        return Result.success(service.latestAnalysis(userId, id, horizonCode));
    }

    @PostMapping("/assets/{id}/quant-analysis/refresh")
    public Result<Map<String, Object>> refresh(@RequestAttribute Long userId,
                                               @PathVariable Long id,
                                               @Valid @RequestBody RefreshRequest request) {
        return Result.success(service.refresh(userId, id, request.horizonCode()));
    }

    @GetMapping("/quant/jobs/{jobId}")
    public Result<Map<String, Object>> job(@RequestAttribute Long userId,
                                           @PathVariable String jobId) {
        return Result.success(service.job(userId, jobId));
    }

    @GetMapping("/quant/strategy-status")
    public Result<Map<String, Object>> strategyStatus(@RequestAttribute Long userId) {
        return Result.success(service.strategyStatus(userId));
    }

    @GetMapping("/paper/account")
    public Result<Map<String, Object>> paperAccount(@RequestAttribute Long userId) {
        return Result.success(service.paperAccount(userId));
    }

    public record RefreshRequest(@NotBlank String horizonCode) {
    }
}
