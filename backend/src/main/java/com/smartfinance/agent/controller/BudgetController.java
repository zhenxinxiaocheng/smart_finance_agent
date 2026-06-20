package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.BudgetRequest;
import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestAttribute Long userId,
                                            @RequestParam(required = false) String month) {
        String targetMonth = month == null || month.isBlank() ? YearMonth.now().toString() : month;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", targetMonth);
        data.put("items", budgetService.getUserBudgets(userId, targetMonth));
        data.put("summary", budgetService.getBudgetSummary(userId, targetMonth));
        return Result.success(data);
    }

    @PutMapping
    public Result<Budget> save(@RequestAttribute Long userId,
                               @Valid @RequestBody BudgetRequest request) {
        int threshold = request.getAlertThreshold() == null ? 80 : request.getAlertThreshold();
        return Result.success(budgetService.setBudget(
                userId,
                request.getCategory(),
                request.getMonth(),
                request.getAmount(),
                threshold
        ));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId,
                               @PathVariable Long id) {
        budgetService.deleteBudget(id, userId);
        return Result.success();
    }
}
