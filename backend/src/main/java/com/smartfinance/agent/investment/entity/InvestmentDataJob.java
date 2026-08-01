package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_data_job")
public class InvestmentDataJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long assetId;
    private Long productId;
    private String jobType;
    private String status;
    private Boolean forceRefresh;
    private Integer recordCount;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String errorMessage;
    private LocalDate requestedStartDate;
    private LocalDate sampleStartDate;
    private LocalDate sampleEndDate;
    private Boolean coverageComplete;
    private String datasetVersion;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
