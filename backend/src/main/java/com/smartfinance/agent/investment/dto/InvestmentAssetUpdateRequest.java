package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvestmentAssetUpdateRequest {
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal quantity;
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal averageCost;
    private String note;
}
