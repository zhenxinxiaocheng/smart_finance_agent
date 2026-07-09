package com.smartfinance.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextConfig {
    private int maxTokens;
    private int reservedOutputTokens;
    private int effectiveBudget;
}