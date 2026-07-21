package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_training_run")
public class QuantTrainingRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String externalJobId;
    private String datasetVersion;
    private String featureSetVersion;
    private String quantConfigVersion;
    private String productType;
    private Integer horizonDays;
    private String status;
    private String metricsJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
