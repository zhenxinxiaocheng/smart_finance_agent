package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("budget_alert")
public class BudgetAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String category;
    private String month;
    private String alertType;
    private String severity;
    private BigDecimal spentAmount;
    private BigDecimal budgetAmount;
    private BigDecimal usagePercent;
    private String message;
    private Integer isRead;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
