package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.AgentAuditResponse;

public interface AgentAuditService {

    AgentAuditResponse overview(Long userId);
}
