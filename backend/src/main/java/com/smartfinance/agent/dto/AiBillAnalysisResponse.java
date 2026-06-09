package com.smartfinance.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiBillAnalysisResponse {
    private String billType;
    private BigDecimal confidence;
    private String ocrText;
    private List<AiCandidateTransaction> candidates = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
