package com.smartfinance.agent.investment.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class InvestmentAssetDetailResponse {
    private InvestmentAssetView asset;
    private List<Map<String, Object>> quoteSeries = List.of();
    private Map<String, Object> technicalAnalysis = Map.of();
    private Map<String, Object> fundamentalAnalysis = Map.of();
    private Map<String, Object> personalizedAction = Map.of();
    private List<Map<String, Object>> financialWarnings = List.of();
    private Map<String, Object> backtestSummary = Map.of();
    private Map<String, Object> aiExplanation = Map.of();
    private Map<String, Object> sourceStatus = Map.of();
    private HorizonProfileResponse analysisPreference;
}
