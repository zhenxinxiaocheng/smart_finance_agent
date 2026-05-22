package com.smartfinance.agent.service;

import java.util.List;
import java.util.Map;

public interface ChatService {

    String chat(Long userId, String message);

    List<Map<String, Object>> getChatHistory(Long userId, int limit);
}
