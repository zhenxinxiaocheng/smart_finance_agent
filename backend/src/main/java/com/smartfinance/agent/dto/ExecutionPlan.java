package com.smartfinance.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {
    private String originalQuery;
    private List<PlanStep> steps;
    private int currentStep;
    private String status;
    private String finalAnswer;
}
