package com.smartfinance.agent.service;

import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    String chat(Long userId, String message);

    String chat(Long userId, Long conversationId, String message);

    SseEmitter streamReactChat(Long userId, String message);

    SseEmitter streamReactChat(Long userId, Long conversationId, String message);

    List<Map<String, Object>> getChatHistory(Long userId, int limit);

    List<Map<String, Object>> getChatHistory(Long userId, Long conversationId, int limit);
}
