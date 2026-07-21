package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_account")
public class InvestmentAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String accountName;
    private String accountType;
    private String baseCurrency;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
