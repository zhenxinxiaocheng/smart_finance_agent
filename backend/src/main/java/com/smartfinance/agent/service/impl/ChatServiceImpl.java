package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final long SSE_TIMEOUT = 125_000L;
    private static final String FALLBACK_RESPONSE = "抱歉，我现在暂时无法处理你的请求，请稍后再试。";

    private final ReActAgentService reactAgentService;
    private final ChatMessageMapper chatMessageMapper;

    public ChatServiceImpl(ReActAgentService reactAgentService, ChatMessageMapper chatMessageMapper) {
        this.reactAgentService = reactAgentService;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public String chat(Long userId, String message) {
        saveMessage(userId, "USER", message);
        String response;
        try {
            response = reactAgentService.run(userId, message).getFinalAnswer();
        } catch (Exception e) {
            log.error("ReActAgent call failed: userId={}, message={}", userId, message, e);
            response = FALLBACK_RESPONSE;
        }
        saveMessage(userId, "ASSISTANT", response);
        return response;
    }

    @Override
    public SseEmitter streamReactChat(Long userId, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        saveMessage(userId, "USER", message);

        CompletableFuture.runAsync(() -> {
            try {
                var result = reactAgentService.run(userId, message, new ReActAgentService.ReActEventListener() {
                    @Override
                    public void onStepStarted(int stepNumber, String summary, String tool) {
                        sendEvent(emitter, "step_started", Map.of(
                                "stepNumber", stepNumber,
                                "summary", summary,
                                "tool", tool
                        ));
                    }

                    @Override
                    public void onStepFinished(int stepNumber, String summary, boolean success) {
                        sendEvent(emitter, "step_finished", Map.of(
                                "stepNumber", stepNumber,
                                "summary", summary,
                                "success", success
                        ));
                    }

                    @Override
                    public void onFinal(String response, String traceId) {
                        sendEvent(emitter, "final", Map.of(
                                "response", response,
                                "traceId", traceId
                        ));
                    }
                });
                saveMessage(userId, "ASSISTANT", result.getFinalAnswer());
                emitter.complete();
            } catch (Exception e) {
                log.error("ReAct SSE call failed: userId={}, message={}", userId, message, e);
                saveMessage(userId, "ASSISTANT", FALLBACK_RESPONSE);
                sendEvent(emitter, "error", Map.of(
                        "message", FALLBACK_RESPONSE,
                        "traceId", ""
                ));
                emitter.complete();
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }

    private void saveMessage(Long userId, String role, String content) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
            msg.setRole(role);
            msg.setContent(content);
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

        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("time", msg.getCreatedAt());
            history.add(item);
        }
        return history;
    }
}
