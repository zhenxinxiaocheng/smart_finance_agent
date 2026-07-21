package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_backtest_run")
public class QuantBacktestRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelVersion;
    private String strategyVersion;
    private String datasetVersion;
    private Integer horizonDays;
    private String status;
    private String metricsJson;
    private String equityCurveUri;
    private String equityCurveHash;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
