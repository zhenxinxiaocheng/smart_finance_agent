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
@TableName("investment_data_quality_snapshot")
public class InvestmentDataQualitySnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String datasetVersion;
    private String productType;
    private String code;
    private String market;
    private String frequency;
    private String adjustType;
    private String provider;
    private String adapterVersion;
    private String qualityConfigVersion;
    private String qualityRuleSetVersion;
    private String qualityStatus;
    private String decision;
    private String enforcementMode;
    private LocalDate requestedStartDate;
    private LocalDate requestedEndDate;
    private LocalDate sampleStartDate;
    private LocalDate sampleEndDate;
    private LocalDateTime fetchedAt;
    private LocalDateTime evaluatedAt;
    private String manifestJson;
    private String reportJson;
    private String secondaryDatasetVersionsJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
