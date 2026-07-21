package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_sync_batch")
public class InvestmentSyncBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String jobType;
    private String provider;
    private String status;
    private Integer rowsSuccess;
    private Integer rowsFailed;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
