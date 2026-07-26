package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_job")
public class QuantJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long assetId;
    private String externalJobId;
    private String jobType;
    private String status;
    private String experimentFingerprint;
    private String errorCode;
    private String errorSummary;
    private String datasetVersion;
    private String featureSetVersion;
    private String quantConfigVersion;
    private String productType;
    private String metricsJson;
    private String modelVersion;
    private String strategyVersion;
    private String horizonProfileVersion;
    private String horizonCode;
    private Integer horizonDays;
    private String resultJson;
    private String userMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
