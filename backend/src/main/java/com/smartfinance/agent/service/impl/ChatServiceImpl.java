package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.ChatService;
import com.smartfinance.agent.service.PendingActionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final ChatLanguageModel chatModel;

    public ChatServiceImpl(ReActAgentService reactAgentService,
                           ChatMessageMapper chatMessageMapper,
                           PendingActionService pendingActionService,
                           AgentRunService agentRunService,
                           ChatLanguageModel chatModel) {
        this.reactAgentService = reactAgentService;
        this.chatMessageMapper = chatMessageMapper;
        this.pendingActionService = pendingActionService;
        this.agentRunService = agentRunService;
        this.chatModel = chatModel;
    }

    @Override
    public String chat(Long userId, String message) {
        List<ChatMessage> recentHistory = loadRecentHistory(userId);
        saveMessage(userId, "USER", message);
        AtomicReference<String> traceRef = new AtomicReference<>();
        String response;
        try {
            if (shouldUseFastChat(message)) {
                String traceId = UUID.randomUUID().toString();
                traceRef.set(traceId);
                agentRunService.startRun(userId, traceId, message);
                response = fastChat(message, recentHistory);
                agentRunService.completeRun(traceId, response);
                saveMessage(userId, "ASSISTANT", response, traceId);
            } else {
                var result = reactAgentService.run(userId, message, recentHistory,
                        runRecorder(userId, message, traceRef, null));
                response = result.getFinalAnswer();
                saveMessage(userId, "ASSISTANT", response, result.getTraceId());
            }
        } catch (Exception e) {
            log.error("ReActAgent call failed: userId={}, message={}", userId, message, e);
            response = FALLBACK_RESPONSE;
            agentRunService.failRun(traceRef.get(), e.getMessage(), response);
            saveMessage(userId, "ASSISTANT", response, traceRef.get());
        }
        return response;
    }

    @Override
    public SseEmitter streamReactChat(Long userId, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        List<ChatMessage> recentHistory = loadRecentHistory(userId);
        saveMessage(userId, "USER", message);
        AtomicReference<String> traceRef = new AtomicReference<>();

        CompletableFuture.runAsync(() -> {
            try {
                if (shouldUseFastChat(message)) {
                    String traceId = UUID.randomUUID().toString();
                    traceRef.set(traceId);
                    agentRunService.startRun(userId, traceId, message);
                    String response = fastChat(message, recentHistory);
                    agentRunService.completeRun(traceId, response);
                    saveMessage(userId, "ASSISTANT", response, traceId);
                    sendEvent(emitter, "final", Map.of(
                            "response", response,
                            "traceId", traceId
                    ));
                } else {
                    var result = reactAgentService.run(userId, message, recentHistory,
                            runRecorder(userId, message, traceRef, emitter));
                    saveMessage(userId, "ASSISTANT", result.getFinalAnswer(), result.getTraceId());
                    var pendingActions = pendingActionService.listPending(userId);
                    if (!pendingActions.isEmpty()) {
                        sendEvent(emitter, "pending_actions", Map.of("actions", pendingActions));
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("ReAct SSE call failed: userId={}, message={}", userId, message, e);
                agentRunService.failRun(traceRef.get(), e.getMessage(), FALLBACK_RESPONSE);
                saveMessage(userId, "ASSISTANT", FALLBACK_RESPONSE, traceRef.get());
                sendEvent(emitter, "error", Map.of(
                        "message", FALLBACK_RESPONSE,
                        "traceId", traceRef.get() == null ? "" : traceRef.get()
                ));
                emitter.complete();
            }
        });

        return emitter;
    }

    private boolean shouldUseFastChat(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String text = message.toLowerCase();
        String[] reactKeywords = {
                "消费", "支出", "收入", "账", "记账", "花了", "预算", "储蓄", "存款",
                "流水", "交易", "分类", "统计", "本月", "上月", "今天", "昨天", "明细",
                "股票", "基金", "行情", "新闻", "汇率", "搜索", "查询", "多少", "分析",
                "month", "today", "yesterday", "previous", "last", "expense", "income",
                "spent", "spend", "budget", "transaction", "category", "stock", "fund",
                "price", "market", "search", "analyze"
        };
        for (String keyword : reactKeywords) {
            if (text.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private String fastChat(String message, List<ChatMessage> recentHistory) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("""
                You are 智财Agent. Reply in Chinese unless the user asks otherwise.
                Be concise. Do not claim to have queried financial records, budgets, realtime markets, or tools.
                If the user asks for account data, bookkeeping, budgets, realtime market data, or calculations that need tools, say briefly that this request should use the agent analysis path.
                """));
        appendFastHistory(messages, recentHistory);
        messages.add(UserMessage.from(message));
        AiMessage response = chatModel.generate(messages).content();
        return response == null || response.text() == null ? "" : response.text();
    }

    private void appendFastHistory(List<dev.langchain4j.data.message.ChatMessage> messages,
                                   List<ChatMessage> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return;
        }
        int start = Math.max(0, recentHistory.size() - 4);
        for (int i = start; i < recentHistory.size(); i++) {
            ChatMessage history = recentHistory.get(i);
            if (history == null || history.getContent() == null || history.getContent().isBlank()) {
                continue;
            }
            String content = history.getContent().trim();
            if ("USER".equalsIgnoreCase(history.getRole())) {
                messages.add(UserMessage.from(content));
            } else if ("ASSISTANT".equalsIgnoreCase(history.getRole())) {
                messages.add(AiMessage.from(content));
            }
        }
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
            }
        };
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }

    private List<ChatMessage> loadRecentHistory(Long userId) {
        try {
            List<ChatMessage> messages = chatMessageMapper.selectRecentByUser(userId, MEMORY_MESSAGE_LIMIT);
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

    private void saveMessage(Long userId, String role, String content) {
        saveMessage(userId, role, content, null);
    }

    private void saveMessage(Long userId, String role, String content, String traceId) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
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
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getUserId, userId)
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
