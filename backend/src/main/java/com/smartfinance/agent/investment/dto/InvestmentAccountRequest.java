package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvestmentAccountRequest {
    @NotBlank
    private String accountName;
    @NotBlank
    private String accountType;
    private String baseCurrency = "CNY";
    private BigDecimal openingCash = BigDecimal.ZERO;
}
