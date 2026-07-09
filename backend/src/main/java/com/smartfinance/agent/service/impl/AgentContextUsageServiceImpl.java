package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.AgentContextService;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentContextUsageService;
import com.smartfinance.agent.service.ChatConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AgentContextUsageServiceImpl implements AgentContextUsageService {

    private static final int MEMORY_MESSAGE_LIMIT = 12;

    private final ChatConversationService conversationService;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentContextService agentContextService;

    public AgentContextUsageServiceImpl(ChatConversationService conversationService,
                                        ChatMessageMapper chatMessageMapper,
                                        AgentContextService agentContextService) {
        this.conversationService = conversationService;
        this.chatMessageMapper = chatMessageMapper;
        this.agentContextService = agentContextService;
    }

    @Override
    public ContextUsageSnapshot previewConversation(Long userId, Long conversationId) {
        ChatConversation conversation = conversationService.ensureConversation(userId, conversationId);
        List<ChatMessage> recentHistory = loadRecentHistory(userId, conversation.getId());
        return agentContextService.build(userId, "", recentHistory, null, conversation.getId()).contextBundle().usage();
    }

    private List<ChatMessage> loadRecentHistory(Long userId, Long conversationId) {
        try {
            List<ChatMessage> messages = chatMessageMapper.selectRecentByConversation(
                    userId, conversationId, MEMORY_MESSAGE_LIMIT);
            List<ChatMessage> ordered = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ordered.add(messages.get(i));
            }
            return ordered;
        } catch (Exception e) {
            log.warn("Load context usage history failed: userId={}, conversationId={}", userId, conversationId, e);
            return List.of();
        }
    }
}
