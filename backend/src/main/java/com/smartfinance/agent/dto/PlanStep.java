package com.smartfinance.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {
    private int stepNumber;
    private String action;
    private String description;
    private String toolName;
    private String parameters;
    private String result;
    private boolean completed;
    private String errorMessage;
}
