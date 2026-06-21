package com.smartfinance.agent.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentMemoryPreferencesResponse {

    private String customInstructions;

    private boolean autoMemoryEnabled;

    private boolean skipToolAssistedMemory;
}
