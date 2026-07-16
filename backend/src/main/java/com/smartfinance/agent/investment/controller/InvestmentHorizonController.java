package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonProfileResponse;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investment/horizon-profile")
public class InvestmentHorizonController {

    private final InvestmentHorizonService service;

    public InvestmentHorizonController(InvestmentHorizonService service) {
        this.service = service;
    }

    @GetMapping
    public Result<HorizonProfileResponse> get(@RequestAttribute Long userId) {
        return Result.success(service.global(userId));
    }

    @PutMapping
    public Result<HorizonProfileResponse> put(@RequestAttribute Long userId,
                                              @Valid @RequestBody HorizonProfileRequest request) {
        return Result.success(service.saveGlobal(userId, request));
    }
}
