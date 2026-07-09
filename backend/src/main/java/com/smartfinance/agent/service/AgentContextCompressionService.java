package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.AgentContextSummary;

public interface AgentContextCompressionService {

    AgentContextSummary compressConversation(Long userId, String traceId, String scope);

    AgentContextSummary compressConversation(Long userId, Long conversationId, String traceId, String scope);

    AgentContextSummary latestSummary(Long userId);

    AgentContextSummary latestConversationSummary(Long userId);

    AgentContextSummary latestConversationSummary(Long userId, Long conversationId);

    AgentContextSummary latestByScope(Long userId, String scope);

    AgentContextSummary latestByScopeAndSourceHash(Long userId, String scope, String sourceHash);

    AgentContextSummary latestByScopeAndSourceHash(Long userId, Long conversationId, String scope, String sourceHash);

    AgentContextSummary saveAutoSummary(Long userId, String sourceType, String summary,
                                        String sourceRefs, String sourceHash, int originalTokens, int compressedTokens);

    AgentContextSummary saveAutoSummary(Long userId, Long conversationId, String sourceType, String summary,
                                        String sourceRefs, String sourceHash, int originalTokens, int compressedTokens);
}
