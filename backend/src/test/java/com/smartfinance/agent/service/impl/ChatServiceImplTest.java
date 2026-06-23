package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock
    private AgentRunService agentRunService;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(reactAgentService, chatMessageMapper, pendingActionService, agentRunService);
    }

    @Test
    void chat_shouldLoadHistoryBeforeSavingCurrentMessageAndPersistTraceSteps() {
        ChatMessage oldUser = message("USER", "current month?");
        ChatMessage oldAssistant = message("ASSISTANT", "spent 100");
        when(chatMessageMapper.selectRecentByUser(1L, 12)).thenReturn(List.of(oldAssistant, oldUser));
        when(reactAgentService.run(eq(1L), eq("previous month?"), org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(3);
                    listener.onRunStarted("trace-1");
                    listener.onStepStarted(1, "Query spending", "get_total_expense");
                    listener.onStepFinished(1, "Query spending", "get_total_expense", "{}", "spent 80", true, null);
                    listener.onFinal("spent 80", "trace-1");
                    return ReActResult.builder()
                            .traceId("trace-1")
                            .finalAnswer("spent 80")
                            .steps(List.of())
                            .build();
                });

        String response = chatService.chat(1L, "previous month?");

        assertEquals("spent 80", response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
        InOrder inOrder = inOrder(chatMessageMapper, reactAgentService);
        inOrder.verify(chatMessageMapper).selectRecentByUser(1L, 12);
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "USER".equals(msg.getRole()) && "previous month?".equals(msg.getContent())));
        inOrder.verify(reactAgentService).run(eq(1L), eq("previous month?"), historyCaptor.capture(), any());
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "ASSISTANT".equals(msg.getRole())
                        && "spent 80".equals(msg.getContent())
                        && "trace-1".equals(msg.getTraceId())));

        assertEquals(List.of(oldUser, oldAssistant), historyCaptor.getValue());
        verify(agentRunService).startRun(1L, "trace-1", "previous month?");
        verify(agentRunService).recordStepStarted(1L, "trace-1", 1, "Query spending", "get_total_expense");
        verify(agentRunService).recordStepFinished(1L, "trace-1", 1, "Query spending", "get_total_expense",
                "{}", true, "spent 80", null);
        verify(agentRunService).completeRun("trace-1", "spent 80");
    }

    @Test
    void getChatHistory_shouldAttachPersistedStepsForAssistantTrace() {
        ChatMessage user = message("USER", "question");
        ChatMessage assistant = message("ASSISTANT", "answer");
        assistant.setTraceId("trace-2");
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(assistant, user));
        when(agentRunService.stepsByTraceIds(List.of("trace-2"))).thenReturn(Map.of(
                "trace-2", List.of(Map.of("stepNumber", 1, "summary", "Query", "status", "done"))));

        List<Map<String, Object>> history = chatService.getChatHistory(1L, 50);

        assertEquals("USER", history.get(0).get("role"));
        assertEquals("ASSISTANT", history.get(1).get("role"));
        assertEquals("trace-2", history.get(1).get("traceId"));
        assertTrue(history.get(1).get("steps") instanceof List<?>);
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
