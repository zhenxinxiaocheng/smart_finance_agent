package com.smartfinance.agent.investment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class InvestmentImportPreviewResponse {
    private Long batchId;
    private String filename;
    private int rowCount;
    private int errorCount;
    private List<InvestmentImportRow> rows;
}
