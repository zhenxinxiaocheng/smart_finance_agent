package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.FinancialAiService;
import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final FinancialAiService financialAiService;
    private final ChatMessageMapper chatMessageMapper;

    public ChatServiceImpl(FinancialAiService financialAiService, ChatMessageMapper chatMessageMapper) {
        this.financialAiService = financialAiService;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public String chat(Long userId, String message) {
        saveMessage(userId, "USER", message);

        String aiResponse;
        try {
            UserIdContext.set(userId);
            aiResponse = financialAiService.chat(userId, message);
        } catch (Exception e) {
            log.error("AI Agent调用失败: userId={}, message={}", userId, message, e);
            aiResponse = "抱歉，我现在暂时无法处理你的问题，请稍后再试。";
        } finally {
            UserIdContext.clear();
        }

        saveMessage(userId, "ASSISTANT", aiResponse);
        return aiResponse;
    }

    private void saveMessage(Long userId, String role, String content) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setUserId(userId);
            msg.setRole(role);
            msg.setContent(content);
            chatMessageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存聊天记录失败: userId={}, role={}", userId, role, e);
        }
    }

    @Override
    public List<Map<String, Object>> getChatHistory(Long userId, int limit) {
        List<ChatMessage> messages = chatMessageMapper.selectRecentByUser(userId, limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> item = new HashMap<>();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("createdAt", msg.getCreatedAt());
            result.add(item);
        }
        return result;
    }
}
