package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_strategy_version")
public class QuantStrategyVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String strategyVersion;
    private String modelVersion;
    private Long userId;
    private Long assetId;
    private String productType;
    private String modelFamily;
    private String horizonCode;
    private String deploymentRole;
    private String status;
    private String validationMetricsJson;
    private LocalDateTime activatedAt;
    private LocalDateTime retiredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
