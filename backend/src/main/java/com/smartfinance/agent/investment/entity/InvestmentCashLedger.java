package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_cash_ledger")
public class InvestmentCashLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long accountId;
    private Long transactionId;
    private String currency;
    private String eventType;
    private BigDecimal amount;
    private Integer externalFlow;
    private LocalDate occurredOn;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
