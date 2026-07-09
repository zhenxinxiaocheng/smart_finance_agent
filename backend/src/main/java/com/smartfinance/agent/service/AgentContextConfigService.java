package com.smartfinance.agent.service;

import com.smartfinance.agent.dto.AgentContextConfig;

public interface AgentContextConfigService {
    AgentContextConfig getCurrentConfig(Long userId);
}