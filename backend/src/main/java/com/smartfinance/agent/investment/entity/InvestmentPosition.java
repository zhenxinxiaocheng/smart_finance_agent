package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_position")
public class InvestmentPosition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long accountId;
    private Long productId;
    private BigDecimal quantity;
    private BigDecimal costAmount;
    private BigDecimal averageCost;
    private BigDecimal realizedPnl;
    private BigDecimal latestPrice;
    private BigDecimal marketValueCny;
    private BigDecimal unrealizedPnlCny;
    private LocalDate dataDate;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
