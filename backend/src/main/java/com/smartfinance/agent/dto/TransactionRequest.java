package com.smartfinance.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TransactionRequest {

    @NotNull(message = "金额不能为空")
    @Positive(message = "金额必须大于0")
    private BigDecimal amount;

    @NotBlank(message = "类型不能为空")
    private String type;

    @NotBlank(message = "分类不能为空")
    private String category;

    private String description;

    @NotNull(message = "交易日期不能为空")
    private LocalDate transactionDate;
}
