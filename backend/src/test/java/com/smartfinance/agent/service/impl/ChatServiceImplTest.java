package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ReActAgentService reactAgentService;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private PendingActionService pendingActionService;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(reactAgentService, chatMessageMapper, pendingActionService);
    }

    @Test
    void chat_shouldLoadHistoryBeforeSavingCurrentMessageAndPassOrderedHistory() {
        ChatMessage oldUser = message("USER", "我这个月花了多少");
        ChatMessage oldAssistant = message("ASSISTANT", "本月支出 100 元");
        when(chatMessageMapper.selectRecentByUser(1L, 12)).thenReturn(List.of(oldAssistant, oldUser));
        when(reactAgentService.run(eq(1L), eq("那上个月呢"), org.mockito.ArgumentMatchers.<List<ChatMessage>>any()))
                .thenReturn(ReActResult.builder()
                        .finalAnswer("上个月支出 80 元")
                        .steps(List.of())
                        .build());

        String response = chatService.chat(1L, "那上个月呢");

        assertEquals("上个月支出 80 元", response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        InOrder inOrder = inOrder(chatMessageMapper, reactAgentService);
        inOrder.verify(chatMessageMapper).selectRecentByUser(1L, 12);
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "USER".equals(msg.getRole()) && "那上个月呢".equals(msg.getContent())));
        inOrder.verify(reactAgentService).run(eq(1L), eq("那上个月呢"), historyCaptor.capture());
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "ASSISTANT".equals(msg.getRole()) && "上个月支出 80 元".equals(msg.getContent())));

        assertEquals(List.of(oldUser, oldAssistant), historyCaptor.getValue());
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
