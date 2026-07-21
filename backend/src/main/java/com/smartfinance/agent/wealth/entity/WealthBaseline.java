package com.smartfinance.agent.wealth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("wealth_baseline")
public class WealthBaseline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private BigDecimal enteredTotalAssets;
    private BigDecimal baselineNonInvestmentBalance;
    private LocalDateTime baselineAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
