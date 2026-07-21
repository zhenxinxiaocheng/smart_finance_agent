package com.smartfinance.agent.wealth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WealthBaselineRequest {
    @NotNull
    @DecimalMin("0")
    private BigDecimal cashBalance;
}
