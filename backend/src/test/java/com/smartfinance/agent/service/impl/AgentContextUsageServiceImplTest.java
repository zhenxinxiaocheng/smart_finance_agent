package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.AgentContextService;
import com.smartfinance.agent.context.ContextBundle;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.ChatConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentContextUsageServiceImplTest {

    @Mock
    private ChatConversationService conversationService;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private AgentContextService agentContextService;

    @Test
    void previewConversation_shouldEstimateUsageFromCurrentConversationHistoryWithoutWritingMessages() {
        when(conversationService.ensureConversation(1L, 7L)).thenReturn(conversation(7L));
        ChatMessage oldest = message("USER", "上个月餐饮花了多少");
        ChatMessage newest = message("ASSISTANT", "上个月餐饮花费 800 元");
        when(chatMessageMapper.selectRecentByConversation(1L, 7L, 12))
                .thenReturn(List.of(newest, oldest));

        ContextUsageSnapshot usage = new ContextUsageSnapshot(
                12000, 1500, 320, 10180, 0.03,
                List.of(), List.of(), List.of());
        when(agentContextService.build(eq(1L), eq(""), any(), isNull(), eq(7L)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ChatMessage> history = invocation.getArgument(2, List.class);
                    assertThat(history).containsExactly(oldest, newest);
                    return new AgentContextService.AgentContext(
                            List.of(),
                            "",
                            "",
                            new ContextBundle(List.of(), usage, List.of(), List.of(), List.of()));
                });

        AgentContextUsageServiceImpl service = new AgentContextUsageServiceImpl(
                conversationService, chatMessageMapper, agentContextService);

        ContextUsageSnapshot result = service.previewConversation(1L, 7L);

        assertThat(result).isSameAs(usage);
        verify(chatMessageMapper, never()).insert(any(ChatMessage.class));
    }

    private ChatConversation conversation(Long id) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setUserId(1L);
        conversation.setTitle("预算复盘");
        conversation.setDeleted(0);
        return conversation;
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1L);
        message.setConversationId(7L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
