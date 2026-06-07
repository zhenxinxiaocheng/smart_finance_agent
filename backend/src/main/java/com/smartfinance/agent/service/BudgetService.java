package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.entity.BudgetAlert;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface BudgetService {
    Budget setBudget(Long userId, String category, String month, BigDecimal amount, Integer threshold);
    Budget getBudget(Long userId, String category, String month);
    List<Budget> getUserBudgets(Long userId, String month);
    void deleteBudget(Long id, Long userId);

    List<BudgetAlert> checkBudgetAlerts(Long userId, String month);
    List<BudgetAlert> getUnreadAlerts(Long userId);
    List<BudgetAlert> getRecentAlerts(Long userId, int limit);
    void markAlertRead(Long alertId, Long userId);

    Map<String, Object> getBudgetSummary(Long userId, String month);
}
