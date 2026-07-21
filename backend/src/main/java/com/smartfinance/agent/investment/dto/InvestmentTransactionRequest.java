package com.smartfinance.agent.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvestmentTransactionRequest {
    @NotNull
    private Long accountId;
    @NotBlank
    private String eventType;
    @NotNull
    private LocalDate tradeDate;
    private LocalDate settlementDate;
    private String currency = "CNY";
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal amount;
    private BigDecimal fee = BigDecimal.ZERO;
    private BigDecimal factor;
    private String source = "MANUAL";
    private String externalRef;
    private Long reversalTransactionId;
    private String note;
    @Valid
    private InvestmentProductRequest product;
}
