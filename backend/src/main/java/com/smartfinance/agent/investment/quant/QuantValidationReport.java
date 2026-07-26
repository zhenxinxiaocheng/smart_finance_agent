package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_validation_report")
public class QuantValidationReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long experimentId;
    private String modelVersion;
    private String lifecycle;
    private Boolean passed;
    private String failureCodesJson;
    private String checksJson;
    private String metricsJson;
    private String datasetVersion;
    private String featureSetVersion;
    private String quantConfigVersion;
    private LocalDateTime createdAt;
}
