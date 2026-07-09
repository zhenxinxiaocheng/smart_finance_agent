package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.mapper.ChatConversationMapper;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.ChatConversationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatConversationServiceImpl implements ChatConversationService {

    private static final String DEFAULT_TITLE = "新对话";
    private static final String HISTORY_TITLE = "历史对话";
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int AUTO_TITLE_LENGTH = 30;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    public ChatConversationServiceImpl(ChatConversationMapper conversationMapper,
                                       ChatMessageMapper chatMessageMapper) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    @Transactional
    public List<ChatConversation> list(Long userId) {
        requireUser(userId);
        archiveOrphanMessages(userId);
        return conversationMapper.selectActiveByUser(userId);
    }

    @Override
    @Transactional
    public ChatConversation create(Long userId, String title) {
        requireUser(userId);
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setTitle(normalizeTitle(title, DEFAULT_TITLE));
        conversation.setDeleted(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    @Transactional
    public ChatConversation rename(Long userId, Long conversationId, String title) {
        ChatConversation conversation = loadOwned(userId, conversationId);
        conversation.setTitle(normalizeTitle(title, DEFAULT_TITLE));
        conversationMapper.updateById(conversation);
        return conversation;
    }

    @Override
    @Transactional
    public void delete(Long userId, Long conversationId) {
        ChatConversation conversation = loadOwned(userId, conversationId);
        int affected = conversationMapper.softDeleteOwned(userId, conversation.getId());
        if (affected <= 0) {
            throw new IllegalArgumentException("Conversation does not exist");
        }
        chatMessageMapper.softDeleteByConversation(userId, conversationId);
    }

    @Override
    @Transactional
    public ChatConversation ensureConversation(Long userId, Long conversationId) {
        requireUser(userId);
        if (conversationId != null) {
            return loadOwned(userId, conversationId);
        }
        List<ChatConversation> conversations = list(userId);
        if (!conversations.isEmpty()) {
            return conversations.get(0);
        }
        return create(userId, DEFAULT_TITLE);
    }

    @Override
    @Transactional
    public void updateTitleFromFirstMessage(Long userId, Long conversationId, String message) {
        ChatConversation conversation = loadOwned(userId, conversationId);
        if (!DEFAULT_TITLE.equals(conversation.getTitle())) {
            return;
        }
        String title = autoTitle(message);
        if (title.isBlank() || DEFAULT_TITLE.equals(title)) {
            return;
        }
        conversation.setTitle(title);
        conversationMapper.updateById(conversation);
    }

    private void archiveOrphanMessages(Long userId) {
        Integer count = chatMessageMapper.countOrphanMessages(userId);
        if (count == null || count <= 0) {
            return;
        }
        ChatConversation historical = conversationMapper.selectHistoricalConversation(userId);
        if (historical == null) {
            historical = create(userId, HISTORY_TITLE);
        }
        chatMessageMapper.assignOrphanMessages(userId, historical.getId());
    }

    private ChatConversation loadOwned(Long userId, Long conversationId) {
        requireUser(userId);
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        ChatConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || conversation.getDeleted() != null && conversation.getDeleted() == 1
                || !userId.equals(conversation.getUserId())) {
            throw new IllegalArgumentException("Conversation does not exist");
        }
        return conversation;
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private static String normalizeTitle(String title, String fallback) {
        String clean = title == null ? "" : title.trim();
        if (clean.isBlank()) {
            clean = fallback;
        }
        return truncate(clean, MAX_TITLE_LENGTH);
    }

    private static String autoTitle(String message) {
        return truncate(message == null ? "" : message.trim().replaceAll("\\s+", " "), AUTO_TITLE_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
