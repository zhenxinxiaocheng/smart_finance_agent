package com.smartfinance.agent.agent;

import com.smartfinance.agent.context.AgentContextProvider;
import com.smartfinance.agent.context.ContextBlock;
import com.smartfinance.agent.context.ContextBudgetService;
import com.smartfinance.agent.context.ContextBundle;
import com.smartfinance.agent.context.ContextRequest;
import com.smartfinance.agent.context.ContextSourceType;
import com.smartfinance.agent.context.ContextTokenEstimator;
import com.smartfinance.agent.context.FinancialProfileContextProvider;
import com.smartfinance.agent.context.LanguageInstructionContextProvider;
import com.smartfinance.agent.context.RagContextProvider;
import com.smartfinance.agent.context.RecentHistoryContextProvider;
import com.smartfinance.agent.context.AgentMemoryContextProvider;
import com.smartfinance.agent.context.HistoryContextCompressor;
import com.smartfinance.agent.context.RagContextCompressor;
import com.smartfinance.agent.context.MemoryContextCompressor;
import com.smartfinance.agent.service.AgentMemoryService;
import com.smartfinance.agent.service.FinancialProfileService;
import com.smartfinance.agent.service.RagKnowledgeService;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class AgentContextService {

    private final List<AgentContextProvider> providers;
    private final ContextBudgetService contextBudgetService;

    @Autowired
    public AgentContextService(List<AgentContextProvider> providers,
                               ContextBudgetService contextBudgetService) {
        this.providers = providers == null ? List.of() : providers;
        this.contextBudgetService = contextBudgetService;
    }

    public AgentContextService(FinancialProfileService financialProfileService,
                               AgentMemoryService agentMemoryService,
                               RagKnowledgeService ragKnowledgeService) {
        ContextTokenEstimator estimator = new ContextTokenEstimator();
        this.contextBudgetService = new ContextBudgetService(estimator, List.of(
                new HistoryContextCompressor(estimator),
                new RagContextCompressor(estimator),
                new MemoryContextCompressor(estimator)
        ));
        this.providers = List.of(
                new FinancialProfileContextProvider(financialProfileService),
                new AgentMemoryContextProvider(agentMemoryService),
                new RagContextProvider(ragKnowledgeService),
                new LanguageInstructionContextProvider(agentMemoryService),
                new RecentHistoryContextProvider(estimator) {{
                    setSummaryTriggerTokens(50);
                    setSummaryKeepMessages(2);
                }}
        );
    }

    public AgentContext build(Long userId, String userMessage) {
        return build(userId, userMessage, List.of());
    }

    public AgentContext build(Long userId,
                              String userMessage,
                              List<com.smartfinance.agent.entity.ChatMessage> recentHistory) {
        return build(userId, userMessage, recentHistory, null, null);
    }

    public AgentContext build(Long userId,
                              String userMessage,
                              List<com.smartfinance.agent.entity.ChatMessage> recentHistory,
                              String traceId) {
        return build(userId, userMessage, recentHistory, traceId, null);
    }

    public AgentContext build(Long userId,
                              String userMessage,
                              List<com.smartfinance.agent.entity.ChatMessage> recentHistory,
                              String traceId,
                              Long conversationId) {
        ContextRequest request = ContextRequest.builder()
                .userId(userId)
                .conversationId(conversationId)
                .userMessage(userMessage)
                .recentHistory(recentHistory)
                .traceId(traceId)
                .agentRole("FINANCE_REACT")
                .runPhase("INITIAL")
                .build();
        List<ContextBlock> blocks = new ArrayList<>();
        for (AgentContextProvider provider : providers) {
            long started = System.nanoTime();
            try {
                List<ContextBlock> provided = provider.provide(request);
                if (provided != null && !provided.isEmpty()) {
                    blocks.addAll(provided);
                }
            } finally {
                log.info("Chat context: traceId={}, provider={}, elapsedMs={}", traceId,
                        provider.getClass().getSimpleName(),
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }

        ContextBundle bundle = contextBudgetService.build(request, blocks);
        String memoryContext = bundle.selectedBlocks().stream()
                .filter(block -> block.sourceType() == ContextSourceType.MEMORY)
                .map(ContextBlock::content)
                .findFirst()
                .orElse("");
        String languageInstruction = bundle.selectedBlocks().stream()
                .filter(block -> block.sourceType() == ContextSourceType.LANGUAGE)
                .map(ContextBlock::content)
                .findFirst()
                .orElse("");
        return new AgentContext(List.copyOf(bundle.messages()), memoryContext, languageInstruction, bundle);
    }

    public record AgentContext(List<ChatMessage> messages,
                               String memoryContext,
                               String languageInstruction,
                               ContextBundle contextBundle) {
    }
}
