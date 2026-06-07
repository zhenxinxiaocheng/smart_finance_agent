package com.smartfinance.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStepRecord {
    private int stepNumber;
    private String summary;
    private String tool;
    private String input;
    private boolean success;
    private String observationSummary;
    private String observation;
    private String errorMessage;
}
