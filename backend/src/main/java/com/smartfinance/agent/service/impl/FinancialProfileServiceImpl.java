package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.FinancialProfileRequest;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import com.smartfinance.agent.service.FinancialProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class FinancialProfileServiceImpl implements FinancialProfileService {

    private final FinancialProfileMapper financialProfileMapper;

    public FinancialProfileServiceImpl(FinancialProfileMapper financialProfileMapper) {
        this.financialProfileMapper = financialProfileMapper;
    }

    @Override
    public FinancialProfile get(Long userId) {
        FinancialProfile profile = financialProfileMapper.selectByUserId(userId);
        return profile == null ? emptyProfile(userId) : profile;
    }

    @Override
    @Transactional
    public FinancialProfile save(Long userId, FinancialProfileRequest request) {
        FinancialProfile profile = financialProfileMapper.selectByUserId(userId);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new FinancialProfile();
            profile.setUserId(userId);
        }
        apply(request, profile);
        if (isNew) {
            financialProfileMapper.insert(profile);
        } else {
            financialProfileMapper.updateById(profile);
        }
        return profile;
    }

    @Override
    public String buildAgentContext(Long userId) {
        FinancialProfile profile = financialProfileMapper.selectByUserId(userId);
        if (profile == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        add(lines, "身份阶段", profile.getLifeStage());
        addMoney(lines, "月收入", profile.getMonthlyIncome());
        addMoney(lines, "固定支出", profile.getFixedExpense());
        add(lines, "风险偏好", riskLabel(profile.getRiskPreference()));
        if (profile.getSavingsGoalAmount() != null || hasText(profile.getSavingsGoalDeadline())) {
            String amount = profile.getSavingsGoalAmount() == null ? "未设置金额" : profile.getSavingsGoalAmount() + " 元";
            String deadline = hasText(profile.getSavingsGoalDeadline()) ? profile.getSavingsGoalDeadline() : "未设置期限";
            lines.add("储蓄目标：" + amount + "，期限：" + deadline);
        }
        addMoney(lines, "月度总预算目标", profile.getMonthlyBudgetGoal());
        add(lines, "补充偏好", profile.getNotes());
        if (lines.isEmpty()) {
            return "";
        }
        return "用户长期财务画像：\n" + String.join("\n", lines);
    }

    private void apply(FinancialProfileRequest request, FinancialProfile profile) {
        profile.setLifeStage(clean(request.getLifeStage()));
        profile.setMonthlyIncome(defaultZero(request.getMonthlyIncome()));
        profile.setFixedExpense(defaultZero(request.getFixedExpense()));
        profile.setRiskPreference(clean(request.getRiskPreference()));
        profile.setSavingsGoalAmount(defaultZero(request.getSavingsGoalAmount()));
        profile.setSavingsGoalDeadline(clean(request.getSavingsGoalDeadline()));
        profile.setMonthlyBudgetGoal(defaultZero(request.getMonthlyBudgetGoal()));
        profile.setNotes(clean(request.getNotes()));
    }

    private FinancialProfile emptyProfile(Long userId) {
        FinancialProfile profile = new FinancialProfile();
        profile.setUserId(userId);
        profile.setMonthlyIncome(BigDecimal.ZERO);
        profile.setFixedExpense(BigDecimal.ZERO);
        profile.setSavingsGoalAmount(BigDecimal.ZERO);
        profile.setMonthlyBudgetGoal(BigDecimal.ZERO);
        profile.setRiskPreference("STEADY");
        return profile;
    }

    private static void add(List<String> lines, String label, String value) {
        if (hasText(value)) {
            lines.add(label + "：" + value);
        }
    }

    private static void addMoney(List<String> lines, String label, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(label + "：" + value + " 元");
        }
    }

    private static String riskLabel(String riskPreference) {
        if ("CONSERVATIVE".equals(riskPreference)) return "保守";
        if ("AGGRESSIVE".equals(riskPreference)) return "进取";
        if ("STEADY".equals(riskPreference)) return "稳健";
        return riskPreference;
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
