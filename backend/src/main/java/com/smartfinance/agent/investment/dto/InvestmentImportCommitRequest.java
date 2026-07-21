package com.smartfinance.agent.investment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class InvestmentImportCommitRequest {
    @NotNull
    private Long batchId;
    private List<Integer> rowNumbers;
}
