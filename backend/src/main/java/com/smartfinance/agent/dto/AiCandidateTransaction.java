package com.smartfinance.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiCandidateTransaction {
    private BigDecimal amount;
    private String type;
    private String category;
    private String description;
    private String transactionDate;
    private BigDecimal confidence;
}
