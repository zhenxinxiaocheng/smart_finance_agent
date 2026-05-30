package com.smartfinance.agent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("expense_category")
public class ExpenseCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String icon;

    private Integer benchmarkMin;

    private Integer benchmarkMax;

    private String benchmarkLabel;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
