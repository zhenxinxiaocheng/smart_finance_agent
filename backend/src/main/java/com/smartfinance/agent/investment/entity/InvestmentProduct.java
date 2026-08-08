package com.smartfinance.agent.investment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("investment_product")
public class InvestmentProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String productType;
    private String fundTypeRaw;
    private String fundCategory;
    private String classificationSource;
    private String classificationVersion;
    private LocalDateTime classifiedAt;
    private String market;
    private String code;
    private String name;
    private String currency;
    private String status;
    private LocalDate inceptionDate;
    private LocalDate historyStartDate;
    private LocalDate historyEndDate;
    private Boolean historyCoverageComplete;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
