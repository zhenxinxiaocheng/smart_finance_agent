package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.entity.BudgetAlert;
import com.smartfinance.agent.service.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final BudgetService budgetService;

    public AlertController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping("/unread")
    public Result<List<BudgetAlert>> getUnreadAlerts(@RequestAttribute Long userId) {
        return Result.success(budgetService.getUnreadAlerts(userId));
    }

    @GetMapping("/recent")
    public Result<List<BudgetAlert>> getRecentAlerts(@RequestParam(defaultValue = "5") int limit,
                                                     @RequestAttribute Long userId) {
        return Result.success(budgetService.getRecentAlerts(userId, limit));
    }

    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable Long id, @RequestAttribute Long userId) {
        budgetService.markAlertRead(id, userId);
        return Result.success(null);
    }
}
