package com.smartfinance.agent.dto;

import lombok.Data;

@Data
public class AgentContextCompressRequest {
    private Long conversationId;
    private String traceId;
    private String scope;
}
