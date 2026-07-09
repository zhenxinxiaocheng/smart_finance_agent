package com.smartfinance.agent.service;

import com.smartfinance.agent.context.ContextUsageSnapshot;

public interface AgentContextUsageService {

    ContextUsageSnapshot previewConversation(Long userId, Long conversationId);
}
