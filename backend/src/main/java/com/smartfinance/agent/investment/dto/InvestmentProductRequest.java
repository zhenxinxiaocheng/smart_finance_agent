package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InvestmentProductRequest {
    @NotBlank
    private String productType;
    @NotBlank
    private String market;
    @NotBlank
    private String code;
    @NotBlank
    private String name;
    private String currency = "CNY";
}
