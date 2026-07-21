package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvestmentAssetCreateRequest {
    @NotBlank
    private String productType;
    @NotBlank
    private String code;
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal quantity;
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal averageCost;
    private String note;
}
