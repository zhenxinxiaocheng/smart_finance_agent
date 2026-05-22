package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("transaction")
public class Transaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private BigDecimal amount;

    private String type;

    private String category;

    private String description;

    private LocalDate transactionDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
