package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_analysis_snapshot")
public class InvestmentAnalysisSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long assetId;
    private String ruleVersion;
    private String preferenceHash;
    private String horizonProfileVersion;
    private String horizonConfigJson;
    private String datasetVersion;
    private String qualityRuleSetVersion;
    private String strategyVersion;
    private String analysisCacheKey;
    private String qualityStatus;
    private Boolean historicalCache;
    private String signalHash;
    private LocalDate quoteDate;
    private String technicalJson;
    private String fundamentalJson;
    private String fundJson;
    private String backtestJson;
    private String sourceStatusJson;
    private String aiExplanation;
    private String analysisStatus;
    private LocalDateTime analyzedAt;
    private LocalDateTime aiUpdatedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
