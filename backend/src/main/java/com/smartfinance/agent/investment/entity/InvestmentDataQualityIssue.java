package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_data_quality_issue")
public class InvestmentDataQualityIssue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long qualitySnapshotId;
    private Integer sequenceNo;
    private String ruleCode;
    private String severity;
    private String outcome;
    private String message;
    private String observedJson;
    private String expectedJson;
    private String affectedDatesJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
