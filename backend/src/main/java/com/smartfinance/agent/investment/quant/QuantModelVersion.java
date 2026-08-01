package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_model_version")
public class QuantModelVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelVersion;
    private String featureSetVersion;
    private String quantConfigVersion;
    private String productType;
    private Integer horizonDays;
    private String status;
    private String deploymentStatus;
    private String economicRole;
    private String optimizationStudyId;
    private Integer optimizationGeneration;
    private String baselineComparisonJson;
    private String artifactUri;
    private String artifactHash;
    private String metricsJson;
    private LocalDateTime trainedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
