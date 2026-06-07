package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.entity.BudgetAlert;
import com.smartfinance.agent.mapper.BudgetAlertMapper;
import com.smartfinance.agent.mapper.BudgetMapper;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.service.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
public class BudgetServiceImpl implements BudgetService {

    private final BudgetMapper budgetMapper;
    private final BudgetAlertMapper budgetAlertMapper;
    private final TransactionMapper transactionMapper;

    public BudgetServiceImpl(BudgetMapper budgetMapper,
                              BudgetAlertMapper budgetAlertMapper,
                              TransactionMapper transactionMapper) {
        this.budgetMapper = budgetMapper;
        this.budgetAlertMapper = budgetAlertMapper;
        this.transactionMapper = transactionMapper;
    }

    @Override
    @Transactional
    public Budget setBudget(Long userId, String category, String month, BigDecimal amount, Integer threshold) {
        LambdaQueryWrapper<Budget> qw = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getCategory, category)
                .eq(Budget::getMonth, month);

        Budget budget = budgetMapper.selectOne(qw);
        if (budget != null) {
            budget.setBudgetAmount(amount);
            if (threshold != null) budget.setAlertThreshold(threshold);
            budgetMapper.updateById(budget);
            log.info("Budget updated: userId={}, category={}, month={}, amount={}", userId, category, month, amount);
            return budget;
        }

        budget = new Budget();
        budget.setUserId(userId);
        budget.setCategory(category);
        budget.setMonth(month);
        budget.setBudgetAmount(amount);
        budget.setAlertThreshold(threshold != null ? threshold : 80);
        budgetMapper.insert(budget);
        log.info("Budget created: userId={}, category={}, month={}, amount={}", userId, category, month, amount);
        return budget;
    }

    @Override
    public Budget getBudget(Long userId, String category, String month) {
        LambdaQueryWrapper<Budget> qw = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getCategory, category)
                .eq(Budget::getMonth, month);
        return budgetMapper.selectOne(qw);
    }

    @Override
    public List<Budget> getUserBudgets(Long userId, String month) {
        LambdaQueryWrapper<Budget> qw = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getUserId, userId)
                .eq(Budget::getMonth, month)
                .orderByAsc(Budget::getCategory);
        return budgetMapper.selectList(qw);
    }

    @Override
    public void deleteBudget(Long id, Long userId) {
        LambdaQueryWrapper<Budget> qw = new LambdaQueryWrapper<Budget>()
                .eq(Budget::getId, id)
                .eq(Budget::getUserId, userId);
        budgetMapper.delete(qw);
    }

    @Override
    public List<BudgetAlert> checkBudgetAlerts(Long userId, String month) {
        List<BudgetAlert> alerts = new ArrayList<>();
        List<Budget> budgets = getUserBudgets(userId, month);
        if (budgets.isEmpty()) return alerts;

        String[] parts = month.split("-");
        LocalDate start = LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        int daysInMonth = start.lengthOfMonth();
        int currentDay = Math.min(LocalDate.now().getDayOfMonth(), daysInMonth);

        for (Budget budget : budgets) {
            String category = budget.getCategory();
            BigDecimal budgetAmount = budget.getBudgetAmount();
            BigDecimal spent;
            if ("ALL".equals(category)) {
                spent = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
            } else {
                spent = transactionMapper.sumByUserAndCategoryAndDateRange(userId, category, start, end);
            }
            if (spent == null) spent = BigDecimal.ZERO;

            if (budgetAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal usagePercent = spent.multiply(BigDecimal.valueOf(100))
                    .divide(budgetAmount, 1, RoundingMode.HALF_UP);

            boolean createAlert = false;
            String alertType = null;
            String severity = null;
            String message = null;

            if (usagePercent.compareTo(BigDecimal.valueOf(100)) >= 0) {
                createAlert = true;
                alertType = "OVERRUN";
                severity = "CRITICAL";
                BigDecimal overAmount = spent.subtract(budgetAmount);
                String catName = "ALL".equals(category) ? "" : category + "";
                message = String.format("%s已超支%.2f元（预算%.0f，已花%.0f）请注意控制消费！",
                        catName, overAmount, budgetAmount, spent);
            } else if (usagePercent.compareTo(BigDecimal.valueOf(budget.getAlertThreshold())) >= 0) {
                createAlert = true;
                alertType = "THRESHOLD";
                severity = "WARNING";
                String catName = "ALL".equals(category) ? "" : category + "";
                message = String.format("%s预算已用%.1f%%，已花%.0f，请注意控制",
                        catName, usagePercent, spent);
            }

            if (createAlert) {
                // Check if same alert already exists today
                LambdaQueryWrapper<BudgetAlert> checkQw = new LambdaQueryWrapper<BudgetAlert>()
                        .eq(BudgetAlert::getUserId, userId)
                        .eq(BudgetAlert::getCategory, category)
                        .eq(BudgetAlert::getMonth, month)
                        .eq(BudgetAlert::getAlertType, alertType)
                        .eq(BudgetAlert::getDeleted, 0)
                        .orderByDesc(BudgetAlert::getCreatedAt)
                        .last("LIMIT 1");
                BudgetAlert existing = budgetAlertMapper.selectOne(checkQw);
                if (existing != null) {
                    // Update existing alert
                    existing.setSpentAmount(spent);
                    existing.setUsagePercent(usagePercent);
                    existing.setMessage(message);
                    budgetAlertMapper.updateById(existing);
                    alerts.add(existing);
                    continue;
                }

                BudgetAlert alert = new BudgetAlert();
                alert.setUserId(userId);
                alert.setCategory(category);
                alert.setMonth(month);
                alert.setAlertType(alertType);
                alert.setSeverity(severity);
                alert.setSpentAmount(spent);
                alert.setBudgetAmount(budgetAmount);
                alert.setUsagePercent(usagePercent);
                alert.setMessage(message);
                alert.setIsRead(0);
                budgetAlertMapper.insert(alert);
                alerts.add(alert);
                log.warn("Budget alert created: userId={}, category={}, type={}, usage={}%", userId, category, alertType, usagePercent);
            }
        }
        return alerts;
    }

    @Override
    public List<BudgetAlert> getUnreadAlerts(Long userId) {
        return budgetAlertMapper.findUnreadAlerts(userId);
    }

    @Override
    public List<BudgetAlert> getRecentAlerts(Long userId, int limit) {
        return budgetAlertMapper.findRecentAlerts(userId, limit);
    }

    @Override
    @Transactional
    public void markAlertRead(Long alertId, Long userId) {
        BudgetAlert alert = budgetAlertMapper.selectById(alertId);
        if (alert != null && alert.getUserId().equals(userId)) {
            alert.setIsRead(1);
            budgetAlertMapper.updateById(alert);
        }
    }

    @Override
    public Map<String, Object> getBudgetSummary(Long userId, String month) {
        List<Budget> budgets = getUserBudgets(userId, month);
        Map<String, Object> summary = new LinkedHashMap<>();

        if (budgets.isEmpty()) {
            summary.put("hasBudget", false);
            summary.put("message", "尚未设置本月预算");
            return summary;
        }

        String[] parts = month.split("-");
        LocalDate start = LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());

        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        List<Map<String, Object>> details = new ArrayList<>();

        for (Budget budget : budgets) {
            String cat = budget.getCategory();
            BigDecimal budgetAmt = budget.getBudgetAmount();
            BigDecimal spent;
            if ("ALL".equals(cat)) {
                spent = transactionMapper.sumByUserAndTypeAndDateRange(userId, "EXPENSE", start, end);
            } else {
                spent = transactionMapper.sumByUserAndCategoryAndDateRange(userId, cat, start, end);
            }
            if (spent == null) spent = BigDecimal.ZERO;
            totalBudget = totalBudget.add(budgetAmt);
            totalSpent = totalSpent.add(spent);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("category", cat);
            detail.put("budget", budgetAmt);
            detail.put("spent", spent);
            detail.put("usagePercent", budgetAmt.compareTo(BigDecimal.ZERO) > 0
                    ? spent.multiply(BigDecimal.valueOf(100)).divide(budgetAmt, 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            details.add(detail);
        }

        summary.put("hasBudget", true);
        summary.put("totalBudget", totalBudget);
        summary.put("totalSpent", totalSpent);
        summary.put("remaining", totalBudget.subtract(totalSpent));
        summary.put("details", details);
        return summary;
    }
}
