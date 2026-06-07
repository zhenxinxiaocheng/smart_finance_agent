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
public class ReActResult {
    private String traceId;
    private String finalAnswer;
    private List<ReActStepRecord> steps;
}
