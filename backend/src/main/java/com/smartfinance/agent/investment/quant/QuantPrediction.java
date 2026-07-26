package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quant_prediction")
public class QuantPrediction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long assetId;
    private String datasetVersion;
    private String featureSetVersion;
    private String modelVersion;
    private String strategyVersion;
    private String modelFamily;
    private String horizonProfileVersion;
    private String horizonCode;
    private Integer horizonDays;
    private LocalDate asOfDate;
    private BigDecimal probabilityPositiveExcess;
    private BigDecimal expectedExcessReturn;
    private BigDecimal intervalLower;
    private BigDecimal intervalUpper;
    private String confidence;
    private String action;
    private BigDecimal targetWeight;
    private String marketRegime;
    private String benchmarkCode;
    private BigDecimal roundTripCostBps;
    private String featureVectorJson;
    private String topFactorsJson;
    private String riskFlagsJson;
    private String backtestSummaryJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
