package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.FinancialProfileRequest;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.service.BudgetService;
import com.smartfinance.agent.service.FinancialProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/financial-profile")
public class FinancialProfileController {

    private final FinancialProfileService financialProfileService;
    private final BudgetService budgetService;

    public FinancialProfileController(FinancialProfileService financialProfileService,
                                      BudgetService budgetService) {
        this.financialProfileService = financialProfileService;
        this.budgetService = budgetService;
    }

    @GetMapping
    public Result<FinancialProfile> get(@RequestAttribute Long userId) {
        return Result.success(financialProfileService.get(userId));
    }

    @PutMapping
    public Result<FinancialProfile> save(@RequestAttribute Long userId,
                                         @Valid @RequestBody FinancialProfileRequest request) {
        FinancialProfile profile = financialProfileService.save(userId, request);
        if (request.getMonthlyBudgetGoal() != null && request.getMonthlyBudgetGoal().signum() > 0) {
            budgetService.setBudget(userId, "ALL", YearMonth.now().toString(), request.getMonthlyBudgetGoal(), 80);
        }
        return Result.success(profile);
    }
}
