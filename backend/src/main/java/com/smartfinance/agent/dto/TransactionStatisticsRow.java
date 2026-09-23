package com.smartfinance.agent.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TransactionStatisticsRow {
    private LocalDate transactionDate;
    private String type;
    private String category;
    private BigDecimal amount;
    private long transactionCount;
}
