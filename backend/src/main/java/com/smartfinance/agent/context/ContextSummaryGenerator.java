package com.smartfinance.agent.context;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ContextSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ContextSummaryGenerator.class);

    private final ChatLanguageModel chatModel;

    public ContextSummaryGenerator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    public String summarize(String sourceLabel, String userMessage, String rawContent, int targetTokens) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isBlank()) {
            return "";
        }
        String label = sourceLabel == null || sourceLabel.isBlank() ? "上下文" : sourceLabel.trim();
        int safeTarget = Math.max(80, targetTokens);
        String systemPrompt = """
                你是一个财务Agent的上下文摘要助手。你的任务是把以下%s压缩成简洁的摘要，保留与用户当前问题相关的关键信息。

                摘要要求：
                - 保留：用户目标、约束条件、已完成步骤、失败尝试、关键数字/日期/分类、工具引用
                - 省略：问候语、重复信息、与当前问题无关的细节
                - 格式：一段中文短文，不超过%d个token
                - 不要编造不存在的细节
                """.formatted(label, safeTarget);
        String userPrompt = """
                用户当前问题：%s

                需要压缩的内容：
                ---
                %s
                ---
                """.formatted(userMessage == null ? "" : userMessage, truncate(content, 8000));
        try {
            var response = chatModel.generate(java.util.List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)));
            String summary = response.content() != null ? response.content().text() : "";
            if (summary != null && !summary.isBlank()) {
                return summary.trim();
            }
            log.warn("Context summarization returned empty, falling back to truncation");
        } catch (Exception e) {
            log.warn("Context summarization failed: {}", e.getMessage());
        }
        return fallbackTruncate(content, safeTarget);
    }

    public String sourceHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String fallbackTruncate(String text, int targetTokens) {
        if (text == null || text.isBlank()) return "";
        int maxChars = Math.max(240, targetTokens * 3);
        return text.length() <= maxChars ? text.trim() : text.substring(0, maxChars).trim() + "...(截断)";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max).trim() + "...";
    }
}
