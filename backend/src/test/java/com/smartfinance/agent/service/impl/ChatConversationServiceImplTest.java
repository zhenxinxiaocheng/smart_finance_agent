package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.mapper.ChatConversationMapper;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatConversationServiceImplTest {

    @Mock
    private ChatConversationMapper conversationMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;

    private ChatConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatConversationServiceImpl(conversationMapper, chatMessageMapper);
    }

    @Test
    void create_shouldPersistDefaultConversationTitle() {
        ChatConversation conversation = service.create(1L, null);

        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(conversation).isSameAs(captor.getValue());
        assertThat(conversation.getUserId()).isEqualTo(1L);
        assertThat(conversation.getTitle()).isEqualTo("新对话");
        assertThat(conversation.getDeleted()).isZero();
    }

    @Test
    void list_shouldArchiveOrphanMessagesIntoHistoricalConversationBeforeReturning() {
        ChatConversation historical = conversation(8L, "历史对话", 1L);
        when(chatMessageMapper.countOrphanMessages(1L)).thenReturn(2);
        when(conversationMapper.selectHistoricalConversation(1L)).thenReturn(historical);
        when(conversationMapper.selectActiveByUser(1L)).thenReturn(List.of(historical));

        List<ChatConversation> conversations = service.list(1L);

        assertThat(conversations).containsExactly(historical);
        verify(chatMessageMapper).assignOrphanMessages(1L, 8L);
        verify(conversationMapper, never()).insert(any(ChatConversation.class));
    }

    @Test
    void rename_shouldRejectConversationOwnedByAnotherUser() {
        ChatConversation other = conversation(9L, "Other", 2L);
        when(conversationMapper.selectById(9L)).thenReturn(other);

        assertThatThrownBy(() -> service.rename(1L, 9L, "Mine"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_shouldSoftDeleteConversationAndItsMessages() {
        ChatConversation existing = conversation(7L, "Budget", 1L);
        when(conversationMapper.selectById(7L)).thenReturn(existing);
        when(conversationMapper.softDeleteOwned(1L, 7L)).thenReturn(1);

        service.delete(1L, 7L);

        verify(conversationMapper).softDeleteOwned(1L, 7L);
        verify(conversationMapper, never()).updateById(existing);
        verify(chatMessageMapper).softDeleteByConversation(1L, 7L);
    }

    private ChatConversation conversation(Long id, String title, Long userId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setTitle(title);
        conversation.setUserId(userId);
        conversation.setDeleted(0);
        return conversation;
    }
}
