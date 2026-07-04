package com.smartfinance.agent.dto;

import lombok.Data;

import java.util.List;

@Data
public class AgentAuditResponse {

    private AgentAuditSummary summary;

    private List<AgentAuditEvent> events;
}
