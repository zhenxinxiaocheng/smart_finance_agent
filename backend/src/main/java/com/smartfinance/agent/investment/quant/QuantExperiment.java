package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_experiment")
public class QuantExperiment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long assetId;
    private Long universeId;
    private String modelFamily;
    private String horizonCode;
    private Integer horizonDays;
    private String status;
    private String executionStatus;
    private String trainingOutcome;
    private String deploymentStatus;
    private String economicRole;
    private String optimizationStudyId;
    private Integer optimizationGeneration;
    private String baselineComparisonJson;
    private String trainingMode;
    private String triggerReason;
    private String parentModelVersion;
    private String bestModelVersion;
    private String searchSummaryJson;
    private String experimentFingerprint;
    private String configJson;
    private String datasetVersion;
    private String featureSetVersion;
    private String quantConfigVersion;
    private String codeVersion;
    private String candidateModelVersion;
    private String logsJson;
    private String errorCode;
    private String errorSummary;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
