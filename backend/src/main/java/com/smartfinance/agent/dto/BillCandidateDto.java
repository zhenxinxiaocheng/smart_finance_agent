package com.smartfinance.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BillCandidateDto {
    private Long id;
    private BigDecimal amount;
    private String type;
    private String category;
    private String description;
    private LocalDate transactionDate;
    private BigDecimal confidence;
    private String status;
    private Long transactionId;
}
