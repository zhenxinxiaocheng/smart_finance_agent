package com.smartfinance.agent.dto;

import lombok.Data;

@Data
public class AgentMemoryPreferencesRequest {

    private String customInstructions;

    private Boolean autoMemoryEnabled;

    private Boolean skipToolAssistedMemory;
}
