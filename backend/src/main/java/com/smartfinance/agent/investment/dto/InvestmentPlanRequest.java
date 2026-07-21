package com.smartfinance.agent.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvestmentPlanRequest {
    @NotNull
    private Long accountId;
    @Valid
    @NotNull
    private InvestmentProductRequest product;
    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal amount;
    @NotBlank
    private String currency;
    @NotBlank
    private String frequency;
    @NotNull
    private Integer executionDay;
    @NotNull
    private LocalDate nextExecutionDate;
}
