package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentReflectionService;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.ChatConversationService;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    @Mock
    private AgentReflectionService agentReflectionService;
    @Mock
    private ChatConversationService conversationService;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        lenient().when(conversationService.ensureConversation(eq(1L), nullable(Long.class)))
                .thenReturn(conversation(99L));
        chatService = new ChatServiceImpl(reactAgentService, chatMessageMapper, pendingActionService,
                agentRunService, agentReflectionService, conversationService);
    }

    @Test
    void chat_shouldLoadHistoryBeforeSavingCurrentMessageAndPersistTraceSteps() {
        ChatMessage oldUser = message("USER", "current month?");
        ChatMessage oldAssistant = message("ASSISTANT", "spent 100");
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of(oldAssistant, oldUser));
        when(reactAgentService.run(eq(1L), eq(99L), eq("previous month?"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
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
        inOrder.verify(chatMessageMapper).selectRecentByConversation(1L, 99L, 12);
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "USER".equals(msg.getRole())
                        && "previous month?".equals(msg.getContent())
                        && Long.valueOf(99L).equals(msg.getConversationId())));
        inOrder.verify(reactAgentService).run(eq(1L), eq(99L), eq("previous month?"), historyCaptor.capture(), any());
        inOrder.verify(chatMessageMapper).insert(argThat(msg ->
                "ASSISTANT".equals(msg.getRole())
                        && "spent 80".equals(msg.getContent())
                        && "trace-1".equals(msg.getTraceId())
                        && Long.valueOf(99L).equals(msg.getConversationId())));

        assertEquals(List.of(oldUser, oldAssistant), historyCaptor.getValue());
        verify(agentRunService).startRun(1L, "trace-1", "previous month?");
        verify(agentRunService).recordStepStarted(1L, "trace-1", 1, "Query spending", "get_total_expense");
        verify(agentRunService).recordStepFinished(1L, "trace-1", 1, "Query spending", "get_total_expense",
                "{}", true, "spent 80", null);
        verify(agentRunService).completeRun("trace-1", "spent 80");
        verify(agentReflectionService).reflectRun(1L, "trace-1");
    }

    @Test
    void chat_shouldLoadOnlyCurrentConversationHistory() {
        ChatMessage otherConversationMessage = message("USER", "other context");
        ChatMessage currentConversationMessage = message("USER", "current context");
        when(conversationService.ensureConversation(1L, 7L)).thenReturn(conversation(7L));
        when(chatMessageMapper.selectRecentByConversation(1L, 7L, 12))
                .thenReturn(List.of(currentConversationMessage));
        when(reactAgentService.run(eq(1L), eq(7L), eq("continue"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-current");
                    listener.onFinal("current answer", "trace-current");
                    return ReActResult.builder()
                            .traceId("trace-current")
                            .finalAnswer("current answer")
                            .steps(List.of())
                            .build();
                });

        String response = chatService.chat(1L, 7L, "continue");

        assertEquals("current answer", response);
        verify(chatMessageMapper).selectRecentByConversation(1L, 7L, 12);
        verify(chatMessageMapper, org.mockito.Mockito.never()).selectRecentByUser(1L, 12);
        verify(reactAgentService).run(eq(1L), eq(7L), eq("continue"), argThat(history ->
                history.size() == 1 && history.contains(currentConversationMessage)
                        && !history.contains(otherConversationMessage)), any());
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

    @Test
    void chat_shouldUseReactPathForPlainConversation() {
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(reactAgentService.run(eq(1L), eq(99L), eq("介绍一下你自己"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-intro");
                    listener.onFinal("我是智财Agent。", "trace-intro");
                    return ReActResult.builder()
                            .traceId("trace-intro")
                            .finalAnswer("我是智财Agent。")
                            .steps(List.of())
                            .build();
                });

        String response = chatService.chat(1L, "介绍一下你自己");

        assertEquals("我是智财Agent。", response);
        verify(agentRunService).startRun(eq(1L), any(), eq("介绍一下你自己"));
        verify(agentRunService).completeRun(any(), eq("我是智财Agent。"));
        verify(agentReflectionService).reflectRun(eq(1L), any(String.class));
    }

    @Test
    void chat_shouldUseReactPathForNaturalMealExpense() {
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(reactAgentService.run(eq(1L), eq(99L), eq("中午吃了15元"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-meal");
                    listener.onStepStarted(1, "正在记录支出", "record_transaction");
                    listener.onStepFinished(1, "正在记录支出", "record_transaction", "{}", "已生成待确认记账", true, null);
                    listener.onFinal("已生成待确认记账，请确认后入账。", "trace-meal");
                    return ReActResult.builder()
                            .traceId("trace-meal")
                            .finalAnswer("已生成待确认记账，请确认后入账。")
                            .steps(List.of())
                            .build();
                });

        String response = chatService.chat(1L, "中午吃了15元");

        assertEquals("已生成待确认记账，请确认后入账。", response);
        verify(reactAgentService).run(eq(1L), eq(99L), eq("中午吃了15元"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any());
        verify(agentRunService).recordStepStarted(1L, "trace-meal", 1, "正在记录支出", "record_transaction");
        verify(agentRunService).recordStepFinished(1L, "trace-meal", 1, "正在记录支出", "record_transaction",
                "{}", true, "已生成待确认记账", null);
    }

    @Test
    void streamReactChat_shouldSendFinalBeforeSlowReflectionCompletes() throws Exception {
        var reflectionStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseReflection = new java.util.concurrent.CountDownLatch(1);
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(pendingActionService.listPending(1L)).thenReturn(List.of());
        when(agentReflectionService.reflectRun(1L, "trace-slow-reflection"))
                .thenAnswer(invocation -> {
                    reflectionStarted.countDown();
                    releaseReflection.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return List.of();
                });
        when(reactAgentService.run(eq(1L), eq(99L), eq("分析预算"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-slow-reflection");
                    listener.onFinal("预算正常。", "trace-slow-reflection");
                    return ReActResult.builder().traceId("trace-slow-reflection")
                            .finalAnswer("预算正常。").steps(List.of()).build();
                });

        try {
            SseEmitter emitter = chatService.streamReactChat(1L, 99L, "分析预算");
            assertThat(reflectionStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(earlySseData(emitter)).contains("event:final").contains("预算正常。");
        } finally {
            releaseReflection.countDown();
        }
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(chatMessageMapper).insert(argThat(message ->
                        "ASSISTANT".equals(message.getRole())
                                && "trace-slow-reflection".equals(message.getTraceId()))));
    }

    @Test
    void streamReactChat_shouldEmitContextUsageSnapshot() {
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(pendingActionService.listPending(1L)).thenReturn(List.of());
        when(reactAgentService.run(eq(1L), eq(99L), eq("分析预算"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-context");
                    listener.onContextUsage(new ContextUsageSnapshot(
                            100, 20, 40, 40, 0.5,
                            List.of(), List.of("older-history"), List.of("rag-knowledge")));
                    listener.onFinal("预算正常。", "trace-context");
                    return ReActResult.builder()
                            .traceId("trace-context")
                            .finalAnswer("预算正常。")
                            .steps(List.of())
                            .build();
                });

        SseEmitter emitter = chatService.streamReactChat(1L, 99L, "分析预算");

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            String sent = earlySseData(emitter);
            assertThat(sent).contains("event:context_usage");
            assertThat(sent).contains("usageRatio=0.5");
            assertThat(sent).contains("compressedKeys=[rag-knowledge]");
        });
    }

    @Test
    void streamReactChat_shouldFinishRunWhenClientDisconnects() throws Exception {
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch continueRun = new CountDownLatch(1);
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(pendingActionService.listPending(1L)).thenReturn(List.of());
        when(reactAgentService.run(eq(1L), eq(99L), eq("分析预算"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenAnswer(invocation -> {
                    ReActAgentService.ReActEventListener listener = invocation.getArgument(4);
                    listener.onRunStarted("trace-disconnected");
                    runStarted.countDown();
                    assertTrue(continueRun.await(2, TimeUnit.SECONDS));
                    listener.onStepStarted(1, "正在分析预算", "get_budget_status");
                    listener.onFinal("预算状态正常。", "trace-disconnected");
                    return ReActResult.builder()
                            .traceId("trace-disconnected")
                            .finalAnswer("预算状态正常。")
                            .steps(List.of())
                            .build();
                });

        SseEmitter emitter = chatService.streamReactChat(1L, 99L, "分析预算");
        assertTrue(runStarted.await(2, TimeUnit.SECONDS));
        emitter.complete();
        continueRun.countDown();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(chatMessageMapper).insert(argThat(message ->
                        "ASSISTANT".equals(message.getRole())
                                && "预算状态正常。".equals(message.getContent())
                                && "trace-disconnected".equals(message.getTraceId()))));
        verify(agentRunService, never()).failRun(any(), any(), any());
        verify(agentRunService).completeRun("trace-disconnected", "预算状态正常。");
    }

    @Test
    void chat_shouldRouteRealtimeMarketLookupThroughReactPath() {
        when(chatMessageMapper.selectRecentByConversation(1L, 99L, 12)).thenReturn(List.of());
        when(reactAgentService.run(eq(1L), eq(99L), eq("查一下纳指100的走势"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any()))
                .thenReturn(ReActResult.builder()
                        .traceId("trace-market")
                        .finalAnswer("已联网查询纳指100走势。")
                        .steps(List.of())
                        .build());

        String response = chatService.chat(1L, "查一下纳指100的走势");

        assertEquals("已联网查询纳指100走势。", response);
        verify(reactAgentService).run(eq(1L), eq(99L), eq("查一下纳指100的走势"),
                org.mockito.ArgumentMatchers.<List<ChatMessage>>any(), any());
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private ChatConversation conversation(Long id) {
        ChatConversation conversation = new ChatConversation();
        conversation.setId(id);
        conversation.setUserId(1L);
        conversation.setTitle("New chat");
        conversation.setDeleted(0);
        return conversation;
    }

    private String earlySseData(SseEmitter emitter) throws Exception {
        Field attemptsField = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        attemptsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Object> attempts = (Set<Object>) attemptsField.get(emitter);
        StringBuilder out = new StringBuilder();
        for (Object attempt : attempts) {
            Object data = attempt.getClass().getMethod("getData").invoke(attempt);
            out.append(data).append("\n");
        }
        return out.toString();
    }
}
