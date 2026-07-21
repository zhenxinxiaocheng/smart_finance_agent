package com.smartfinance.agent.investment.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class InvestmentImportRow {
    private int rowNumber;
    private InvestmentTransactionRequest transaction;
    private List<String> errors = new ArrayList<>();

    public boolean isValid() {
        return errors == null || errors.isEmpty();
    }
}
