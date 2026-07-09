package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.service.AgentMemoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AgentMemoryContextProvider implements AgentContextProvider {

    private final AgentMemoryService agentMemoryService;

    public AgentMemoryContextProvider(AgentMemoryService agentMemoryService) {
        this.agentMemoryService = agentMemoryService;
    }

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        AgentMemoryPreferencesResponse preferences = agentMemoryService.getPreferences(request.userId());
        String instructions = preferences == null ? "" : preferences.getCustomInstructions();
        List<AgentMemory> memories = agentMemoryService.retrieveRelevantMemories(request.userId(), request.userMessage(), 10);
        if (memories == null) {
            memories = List.of();
        }
        if ((instructions == null || instructions.isBlank()) && memories.isEmpty()) {
            return List.of();
        }
        String memoryLines = memories.stream()
                .map(memory -> "- %s/%s: %s".formatted(clean(memory.getMemoryType()),
                        clean(memory.getMemoryKey()), clean(memory.getMemoryValue())))
                .collect(Collectors.joining("\n"));
        String content = """
                系统资料：下面是用户可编辑的 Agent 长期指令和按当前问题召回的长期记忆。
                必须优先遵守其中的回答风格、语言偏好、分类偏好和 Agent 使用偏好；如果和当前问题明确要求冲突，以当前问题为准。
                § USER PROFILE
                %s

                § MEMORY
                %s
                """.formatted(instructions == null ? "" : instructions, memoryLines).trim();
        return List.of(ContextBlock.builder()
                .key("agent-memory")
                .displayName("长期记忆")
                .sourceType(ContextSourceType.MEMORY)
                .content(content)
                .priority(78)
                .required(false)
                .relevanceScore(memories.isEmpty() ? 0.4 : 0.9)
                .displayPolicy(ContextDisplayPolicy.HIDDEN)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build());
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
