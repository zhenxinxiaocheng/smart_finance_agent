package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investment_horizon_profile")
public class InvestmentHorizonProfile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String scopeType;
    private Long assetId;
    private Integer version;
    private String templateVersion;
    private String source;
    private Boolean active;
    private LocalDateTime effectiveFrom;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
