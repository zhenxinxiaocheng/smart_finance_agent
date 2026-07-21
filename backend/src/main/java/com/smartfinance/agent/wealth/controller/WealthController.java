package com.smartfinance.agent.wealth.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.wealth.dto.WealthBaselineRequest;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wealth")
public class WealthController {

    private final WealthService wealthService;

    public WealthController(WealthService wealthService) {
        this.wealthService = wealthService;
    }

    @GetMapping("/overview")
    public Result<WealthOverviewResponse> overview(@RequestAttribute Long userId) {
        return Result.success(wealthService.overview(userId));
    }

    @PutMapping("/baseline")
    public Result<WealthOverviewResponse> setBaseline(@RequestAttribute Long userId,
                                                       @Valid @RequestBody WealthBaselineRequest request) {
        return Result.success(wealthService.setBaseline(userId, request.getCashBalance()));
    }
}
