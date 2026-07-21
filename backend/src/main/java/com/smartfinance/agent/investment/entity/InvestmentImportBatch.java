package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_import_batch")
public class InvestmentImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String originalFilename;
    private String status;
    private String payload;
    private Integer rowCount;
    private Integer errorCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
