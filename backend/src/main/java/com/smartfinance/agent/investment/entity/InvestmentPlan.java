package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_plan")
public class InvestmentPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long accountId;
    private Long productId;
    private BigDecimal amount;
    private String currency;
    private String frequency;
    private Integer executionDay;
    private LocalDate nextExecutionDate;
    private LocalDate lastExecutionDate;
    private BigDecimal lastExecutionAmount;
    private BigDecimal lastExecutionPrice;
    private Integer executionCount;
    private String lastExecutionStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastExecutionMessage;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private String productName;
    @TableField(exist = false)
    private String productCode;
    @TableField(exist = false)
    private String market;
}
