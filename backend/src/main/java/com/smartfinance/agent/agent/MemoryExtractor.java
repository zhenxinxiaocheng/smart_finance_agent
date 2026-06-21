package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.service.AgentMemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class MemoryExtractor {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "CATEGORY_PREFERENCE",
            "RESPONSE_STYLE",
            "ANALYSIS_PREFERENCE",
            "AGENT_PREFERENCE"
    );

    private final ChatLanguageModel chatModel;
    private final AgentMemoryService agentMemoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryExtractor(ChatLanguageModel chatModel, AgentMemoryService agentMemoryService) {
        this.chatModel = chatModel;
        this.agentMemoryService = agentMemoryService;
    }

    public void extractAndSave(Long userId, String userMessage, String finalAnswer) {
        if (userId == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        try {
            String raw = chatModel.generate(messages(userMessage, finalAnswer)).content().text();
            JsonNode root = parse(raw);
            JsonNode memories = root == null ? null : root.get("memories");
            if (memories == null || !memories.isArray()) {
                return;
            }
            for (JsonNode memory : memories) {
                String type = text(memory, "type");
                if (!ALLOWED_TYPES.contains(type)) {
                    continue;
                }
                agentMemoryService.upsertAutoMemory(userId, type, text(memory, "key"),
                        text(memory, "value"), memory.path("confidence").asDouble(0), userMessage);
            }
        } catch (Exception e) {
            log.warn("Memory extraction skipped: userId={}, error={}", userId, e.getMessage());
        }
    }

    private List<ChatMessage> messages(String userMessage, String finalAnswer) {
        return List.of(
                SystemMessage.from("""
                        你是长期记忆提取器。只提取低风险、未来会影响回答或行动的稳定偏好。
                        只允许类型：CATEGORY_PREFERENCE, RESPONSE_STYLE, ANALYSIS_PREFERENCE, AGENT_PREFERENCE。
                        不要提取收入、资产、负债、银行卡、身份证、手机号、密码、API key、一次性问题或模型推测。
                        只输出严格 JSON：{"memories":[{"type":"...","key":"...","value":"...","confidence":0.0}]}
                        没有可记忆内容时输出 {"memories":[]}。
                        """),
                UserMessage.from("""
                        用户消息：%s
                        助手回答：%s
                        """.formatted(userMessage, finalAnswer == null ? "" : finalAnswer))
        );
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
