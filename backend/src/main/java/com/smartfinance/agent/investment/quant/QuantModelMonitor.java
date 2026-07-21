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
@TableName("quant_model_monitor")
public class QuantModelMonitor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelVersion;
    private LocalDate monitoredOn;
    private BigDecimal brierScore;
    private BigDecimal realizedExcessReturn;
    private BigDecimal drawdown;
    private String driftStatus;
    private String evidenceJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
