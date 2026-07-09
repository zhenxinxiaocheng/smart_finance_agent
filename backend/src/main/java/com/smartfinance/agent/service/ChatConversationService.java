package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.ChatConversation;

import java.util.List;

public interface ChatConversationService {

    List<ChatConversation> list(Long userId);

    ChatConversation create(Long userId, String title);

    ChatConversation rename(Long userId, Long conversationId, String title);

    void delete(Long userId, Long conversationId);

    ChatConversation ensureConversation(Long userId, Long conversationId);

    void updateTitleFromFirstMessage(Long userId, Long conversationId, String message);
}
