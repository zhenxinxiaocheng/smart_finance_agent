package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.entity.ChatConversation;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentReflectionService;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.ChatConversationService;
import com.smartfinance.agent.service.ChatService;
import com.smartfinance.agent.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final long SSE_TIMEOUT = 125_000L;
    private static final int MEMORY_MESSAGE_LIMIT = 12;
    private static final String FALLBACK_RESPONSE = "抱歉，我现在暂时无法处理你的请求，请稍后再试。";

    private final ReActAgentService reactAgentService;
    private final ChatMessageMapper chatMessageMapper;
    private final PendingActionService pendingActionService;
    private final AgentRunService agentRunService;
    private final AgentReflectionService agentReflectionService;
    private final ChatConversationService conversationService;

    public ChatServiceImpl(ReActAgentService reactAgentService,
                           ChatMessageMapper chatMessageMapper,
                           PendingActionService pendingActionService,
                           AgentRunService agentRunService,
                           AgentReflectionService agentReflectionService,
                           ChatConversationService conversationService) {
        this.reactAgentService = reactAgentService;
        this.chatMessageMapper = chatMessageMapper;
        this.pendingActionService = pendingActionService;
        this.agentRunService = agentRunService;
        this.agentReflectionService = agentReflectionService;
        this.conversationService = conversationService;
    }

    @Override
    public String chat(Long userId, String message) {
        return chat(userId, null, message);
    }

    @Override
    public String chat(Long userId, Long conversationId, String message) {
        ChatConversation conversation = conversationService.ensureConversation(userId, conversationId);
        Long activeConversationId = conversation.getId();
        List<ChatMessage> recentHistory = loadRecentHistory(userId, activeConversationId);
        saveMessage(userId, activeConversationId, "USER", message);
        conversationService.updateTitleFromFirstMessage(userId, activeConversationId, message);
        AtomicReference<String> traceRef = new AtomicReference<>();
        String response;
        try {
            var result = reactAgentService.run(userId, activeConversationId, message, recentHistory,
                    runRecorder(userId, message, traceRef, null));
            response = result.getFinalAnswer();
            saveMessage(userId, activeConversationId, "ASSISTANT", response, result.getTraceId());
        } catch (Exception e) {
            log.error("ReActAgent call failed: userId={}, message={}", userId, message, e);
            response = FALLBACK_RESPONSE;
            agentRunService.failRun(traceRef.get(), e.getMessage(), response);
            saveMessage(userId, activeConversationId, "ASSISTANT", response, traceRef.get());
        }
        return response;
    }

    @Override
    public SseEmitter streamReactChat(Long userId, String message) {
        return streamReactChat(userId, null, message);
    }

    @Override
    public SseEmitter streamReactChat(Long userId, Long conversationId, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        ChatConversation conversation = conversationService.ensureConversation(userId, conversationId);
        Long activeConversationId = conversation.getId();
        List<ChatMessage> recentHistory = loadRecentHistory(userId, activeConversationId);
        saveMessage(userId, activeConversationId, "USER", message);
        conversationService.updateTitleFromFirstMessage(userId, activeConversationId, message);
        AtomicReference<String> traceRef = new AtomicReference<>();

        CompletableFuture.runAsync(() -> {
            try {
                var result = reactAgentService.run(userId, activeConversationId, message, recentHistory,
                        runRecorder(userId, message, traceRef, emitter));
                saveMessage(userId, activeConversationId, "ASSISTANT", result.getFinalAnswer(), result.getTraceId());
                var pendingActions = pendingActionService.listPending(userId);
                if (!pendingActions.isEmpty()) {
                    sendEvent(emitter, "pending_actions", Map.of("actions", pendingActions));
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("ReAct SSE call failed: userId={}, message={}", userId, message, e);
                agentRunService.failRun(traceRef.get(), e.getMessage(), FALLBACK_RESPONSE);
                saveMessage(userId, activeConversationId, "ASSISTANT", FALLBACK_RESPONSE, traceRef.get());
                sendEvent(emitter, "error", Map.of(
                        "message", FALLBACK_RESPONSE,
                        "traceId", traceRef.get() == null ? "" : traceRef.get()
                ));
                emitter.complete();
            }
        });

        return emitter;
    }

    private ReActAgentService.ReActEventListener runRecorder(Long userId,
                                                             String message,
                                                             AtomicReference<String> traceRef,
                                                             SseEmitter emitter) {
        return new ReActAgentService.ReActEventListener() {
            @Override
            public void onRunStarted(String traceId) {
                traceRef.set(traceId);
                agentRunService.startRun(userId, traceId, message);
            }

            @Override
            public void onStepStarted(int stepNumber, String summary, String tool) {
                agentRunService.recordStepStarted(userId, traceRef.get(), stepNumber, summary, tool);
                if (emitter != null) {
                    sendEvent(emitter, "step_started", Map.of(
                            "stepNumber", stepNumber,
                            "summary", summary,
                            "tool", tool
                    ));
                }
            }

            @Override
            public void onStepFinished(int stepNumber,
                                       String summary,
                                       String tool,
                                       String input,
                                       String observationSummary,
                                       boolean success,
                                       String errorMessage) {
                agentRunService.recordStepFinished(userId, traceRef.get(), stepNumber, summary, tool, input,
                        success, observationSummary, errorMessage);
                if (emitter != null) {
                    sendEvent(emitter, "step_finished", Map.of(
                            "stepNumber", stepNumber,
                            "summary", success ? "步骤完成" : "步骤失败",
                            "success", success
                    ));
                }
            }

            @Override
            public void onFinal(String response, String traceId) {
                agentRunService.completeRun(traceId, response);
                if (emitter != null) {
                    sendEvent(emitter, "final", Map.of(
                            "response", response,
                            "traceId", traceId
                    ));
                }
                reflectRunQuietly(userId, traceId);
            }

            @Override
            public void onContextUsage(ContextUsageSnapshot usage) {
                if (emitter != null && usage != null) {
                    sendEvent(emitter, "context_usage", Map.of("usage", usage));
                }
            }
        };
    }

    private void reflectRunQuietly(Long userId, String traceId) {
        try {
            if (traceId != null && !traceId.isBlank()) {
                agentReflectionService.reflectRun(userId, traceId);
            }
        } catch (Exception e) {
            log.warn("Agent reflection failed: userId={}, traceId={}, error={}", userId, traceId, e.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            log.debug("Skip SSE event because client connection is unavailable: event={}, error={}",
                    eventName, e.getMessage());
        }
    }

    private List<ChatMessage> loadRecentHistory(Long userId, Long conversationId) {
        try {
            List<ChatMessage> messages = chatMessageMapper.selectRecentByConversation(userId, conversationId, MEMORY_MESSAGE_LIMIT);
            List<ChatMessage> ordered = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ordered.add(messages.get(i));
            }
            return ordered;
        } catch (Exception e) {
            log.warn("Load chat history failed: userId={}", userId, e);
            return List.of();
        }
    }

    private void saveMessage(Long userId, Long conversationId, String role, String content) {
        saveMessage(userId, conversationId, role, content, null);
    }

    private void saveMessage(Long userId, Long conversationId, String role, String content, String traceId) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
            msg.setConversationId(conversationId);
            msg.setRole(role);
            msg.setContent(content);
            if (traceId != null && !traceId.isBlank()) {
                msg.setTraceId(traceId);
            }
            chatMessageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("Save chat message failed: userId={}, role={}", userId, role, e);
        }
    }

    @Override
    public List<Map<String, Object>> getChatHistory(Long userId, int limit) {
        ChatConversation conversation = conversationService.ensureConversation(userId, null);
        return getChatHistory(userId, conversation.getId(), limit);
    }

    @Override
    public List<Map<String, Object>> getChatHistory(Long userId, Long conversationId, int limit) {
        ChatConversation conversation = conversationService.ensureConversation(userId, conversationId);
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
                        .eq(ChatMessage::getConversationId, conversation.getId())
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + limit));

        List<ChatMessage> ordered = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ordered.add(messages.get(i));
        }

        List<String> traceIds = ordered.stream()
                .map(ChatMessage::getTraceId)
                .filter(traceId -> traceId != null && !traceId.isBlank())
                .toList();
        Map<String, List<Map<String, Object>>> stepsByTrace = agentRunService.stepsByTraceIds(traceIds);

        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatMessage msg : ordered) {
            Map<String, Object> item = new HashMap<>();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("time", msg.getCreatedAt());
            if (msg.getTraceId() != null && !msg.getTraceId().isBlank()) {
                item.put("traceId", msg.getTraceId());
                item.put("steps", stepsByTrace.getOrDefault(msg.getTraceId(), List.of()));
            }
            history.add(item);
        }
        return history;
    }
}
