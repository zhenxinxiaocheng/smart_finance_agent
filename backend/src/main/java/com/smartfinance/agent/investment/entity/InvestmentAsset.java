package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("investment_asset")
public class InvestmentAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long accountId;
    private Long productId;
    private BigDecimal quantity;
    private BigDecimal averageCost;
    private String note;
    private Long currentTransactionId;
    private String syncStatus;
    private String syncError;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
