package com.smartfinance.agent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class FinancialProfileRequest {

    @Size(max = 50, message = "身份阶段不能超过50个字符")
    private String lifeStage;

    @DecimalMin(value = "0.00", message = "月收入不能为负数")
    private BigDecimal monthlyIncome;

    @DecimalMin(value = "0.00", message = "固定支出不能为负数")
    private BigDecimal fixedExpense;

    @Pattern(regexp = "CONSERVATIVE|STEADY|AGGRESSIVE|", message = "风险偏好不合法")
    private String riskPreference;

    @DecimalMin(value = "0.00", message = "储蓄目标不能为负数")
    private BigDecimal savingsGoalAmount;

    @Pattern(regexp = "\\d{4}-\\d{2}|", message = "目标期限格式应为yyyy-MM")
    private String savingsGoalDeadline;

    @DecimalMin(value = "0.00", message = "月度预算目标不能为负数")
    private BigDecimal monthlyBudgetGoal;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;
}
