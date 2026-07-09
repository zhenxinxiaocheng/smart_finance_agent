package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.service.AgentMemoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class LanguageInstructionContextProvider implements AgentContextProvider {

    private final AgentMemoryService agentMemoryService;

    public LanguageInstructionContextProvider(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        String instruction = preferredLanguageInstruction(request);
        if (instruction.isBlank()) {
            return List.of();
        }
        return List.of(ContextBlock.builder()
                .key("language-instruction")
                .displayName("语言要求")
                .sourceType(ContextSourceType.LANGUAGE)
                .content(instruction)
                .priority(100)
                .required(true)
                .relevanceScore(1.0)
                .displayPolicy(ContextDisplayPolicy.VISIBLE)
                .overflowStrategy(ContextOverflowStrategy.KEEP)
                .build());
    }

    public String preferredLanguageInstruction(ContextRequest request) {
        String current = request.userMessage() == null ? "" : request.userMessage().toLowerCase(Locale.ROOT);
        String memory = "";
        try {
            AgentMemoryPreferencesResponse preferences = agentMemoryService.getPreferences(request.userId());
            String custom = preferences == null ? "" : preferences.getCustomInstructions();
            List<AgentMemory> memories = agentMemoryService.retrieveRelevantMemories(request.userId(), request.userMessage(), 10);
            if (memories == null) {
                memories = List.of();
            }
            String relevant = memories.stream()
                    .map(AgentMemory::getMemoryValue)
                    .collect(Collectors.joining("\n"));
            memory = ((custom == null ? "" : custom) + "\n" + relevant).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            memory = "";
        }
        if (asksForChinese(current)) {
            return "系统语言要求：最终 answer 必须使用中文。";
        }
        if (asksForEnglish(current) || asksForEnglish(memory)) {
            return "System language requirement: the final answer must be written in English. Keep all facts, amounts, and dates unchanged.";
        }
        return "";
    }

    private boolean asksForEnglish(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("english")
                || text.contains("英语")
                || text.contains("英文")
                || text.contains("用英")
                || text.contains("英語")
                || lower.contains("respond in en")
                || lower.contains("reply in en");
    }

    private boolean asksForChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("中文")
                || text.contains("汉语")
                || text.contains("普通话")
                || text.contains("用中")
                || lower.contains("chinese");
    }
}
