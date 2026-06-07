package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.entity.BudgetAlert;
import com.smartfinance.agent.service.BudgetService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BudgetTool {
    private final BudgetService budgetService;
    public BudgetTool(BudgetService budgetService) { this.budgetService = budgetService; }

    @Tool("Set monthly budget for a category")
    public String setBudget(@P("Category name") String category, @P("Amount") BigDecimal amount, @P("Month yyyy-MM") String month) {
        Long userId = UserIdContext.get();
        if (userId == null) return "Cannot get user info";
        if (month == null || month.isBlank()) month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        budgetService.setBudget(userId, category, month, amount, null);
        return String.format("Set budget for %s %s: %.2f", month, category, amount);
    }

    @Tool("Check budget status for a month")
    public String getBudgetStatus(@P("Month yyyy-MM") String month) {
        Long userId = UserIdContext.get();
        if (userId == null) return "Cannot get user info";
        if (month == null || month.isBlank()) month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Map<String, Object> summary = budgetService.getBudgetSummary(userId, month);
        if (!(boolean) summary.get("hasBudget")) return "No budget set for " + month;
        StringBuilder sb = new StringBuilder();
        sb.append("[Budget ").append(month).append("]\n");
        sb.append("Total: ").append(summary.get("totalBudget")).append("\n");
        sb.append("Spent: ").append(summary.get("totalSpent")).append("\n");
        sb.append("Left: ").append(summary.get("remaining"));
        return sb.toString();
    }

    @Tool("Check unread budget alerts")
    public String checkAlerts() {
        Long userId = UserIdContext.get();
        if (userId == null) return "Cannot get user info";
        List<BudgetAlert> alerts = budgetService.getUnreadAlerts(userId);
        if (alerts.isEmpty()) return "No alerts.";
        StringBuilder sb = new StringBuilder("Alerts:\n");
        for (BudgetAlert a : alerts) { sb.append("- ").append(a.getMessage()).append("\n"); budgetService.markAlertRead(a.getId(), userId); }
        return sb.toString();
    }

    @Tool("Get recent alert history")
    public String getAlertHistory(@P("Limit") int limit) {
        Long userId = UserIdContext.get();
        if (userId == null) return "Cannot get user info";
        if (limit <= 0 || limit > 20) limit = 5;
        List<BudgetAlert> alerts = budgetService.getRecentAlerts(userId, limit);
        if (alerts.isEmpty()) return "No history.";
        StringBuilder sb = new StringBuilder("[Alerts]\n");
        for (BudgetAlert a : alerts) sb.append("- ").append(a.getMessage()).append("\n");
        return sb.toString();
    }
}