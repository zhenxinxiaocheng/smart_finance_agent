package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("financial_profile")
public class FinancialProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String lifeStage;
    private BigDecimal monthlyIncome;
    private BigDecimal fixedExpense;
    private String riskPreference;
    private BigDecimal savingsGoalAmount;
    private String savingsGoalDeadline;
    private BigDecimal monthlyBudgetGoal;
    private String notes;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
