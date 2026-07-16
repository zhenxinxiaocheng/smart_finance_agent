package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_horizon_setting")
public class InvestmentHorizonSetting {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long profileId;
    private String horizonCode;
    private String displayName;
    private Integer sortOrder;
    private Integer minHoldingDays;
    private Integer maxHoldingDays;
    @TableField("is_primary")
    private Boolean primary;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
