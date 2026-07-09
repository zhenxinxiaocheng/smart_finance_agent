package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.service.AgentContextCompressionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RecentHistoryContextProvider implements AgentContextProvider {

    private final ContextTokenEstimator estimator;
    private final AgentContextCompressionService compressionService;

    public RecentHistoryContextProvider(ContextTokenEstimator estimator) {
        this(estimator, null);
    }

    @Autowired
    public RecentHistoryContextProvider(ContextTokenEstimator estimator,
                                        AgentContextCompressionService compressionService) {
        this.estimator = estimator;
        this.compressionService = compressionService;
    }

    @Value("${agent.context.summary-keep-messages:20}")
    private int summaryKeepMessages = 20;

    @Value("${agent.context.summary-trigger-tokens:4000}")
    private int summaryTriggerTokens = 4000;

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        AgentContextSummary summary = latestSummary(request);
        Long coveredUntil = summary == null ? null : summary.getCoveredUntilMessageId();
        List<HistoryEntry> entries = request.recentHistory() == null ? List.of() : request.recentHistory().stream()
                .filter(history -> history != null && history.getContent() != null && !history.getContent().isBlank())
                .filter(history -> coveredUntil == null || history.getId() == null || history.getId() > coveredUntil)
                .map(history -> new HistoryEntry(
                        history.getRole() == null ? "" : history.getRole().trim().toUpperCase(),
                        history.getContent().trim()))
                .filter(entry -> "USER".equals(entry.role()) || "ASSISTANT".equals(entry.role()))
                .toList();

        java.util.ArrayList<ContextBlock> blocks = new java.util.ArrayList<>();
        if (summary != null && summary.getSummary() != null && !summary.getSummary().isBlank()) {
            blocks.add(ContextBlock.builder()
                    .key("compressed-summary")
                    .displayName("压缩对话摘要")
                    .sourceType(ContextSourceType.COMPRESSED_SUMMARY)
                    .content(summary.getSummary())
                    .priority(82)
                    .required(false)
                    .relevanceScore(0.8)
                    .displayPolicy(ContextDisplayPolicy.NAME_ONLY)
                    .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                    .storageRef(summary.getTraceId())
                    .updatedAt(summary.getUpdatedAt())
                    .build());
        }
        if (entries.isEmpty()) {
            return blocks;
        }

        // 估算总对话 token 数
        int estimatedTokens = estimateTokens(entries);
        // 动态计算保护消息数：至少保留4条，最多保留配置值
        int keepCount = Math.max(4, Math.min(summaryKeepMessages, entries.size()));

        // 仅当对话超过触发阈值时才拆分 older-history
        int protectedStart;
        if (estimatedTokens > summaryTriggerTokens) {
            protectedStart = Math.max(0, entries.size() - keepCount);
        } else {
            protectedStart = 0; // 没超阈值，全部作为最近对话
        }

        if (protectedStart > 0) {
            String older = entries.subList(0, protectedStart).stream()
                    .map(entry -> "旧 %s: %s".formatted(entry.role(), entry.content()))
                    .collect(Collectors.joining("\n"));
            blocks.add(ContextBlock.builder()
                    .key("older-history")
                    .displayName("较早历史")
                    .sourceType(ContextSourceType.HISTORY)
                    .content(older)
                    .priority(42)
                    .required(false)
                    .relevanceScore(0.5)
                    .displayPolicy(ContextDisplayPolicy.NAME_ONLY)
                    .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                    .build());
        }

        String recent = entries.subList(protectedStart, entries.size()).stream()
                .map(entry -> ("USER".equals(entry.role()) ? "历史用户消息：" : "历史助手回复：") + entry.content())
                .collect(Collectors.joining("\n"));
        blocks.add(ContextBlock.builder()
                .key("recent-history")
                .displayName("最近对话")
                .sourceType(ContextSourceType.HISTORY)
                .content("""
                        下面是该用户最近的对话历史，仅用于理解上下文指代。
                        如果用户提到“刚才、之前、继续、上一个、那”等表达，请优先结合这些历史理解。
                        %s
                        """.formatted(recent).trim())
                .priority(80)
                .required(false)
                .relevanceScore(0.95)
                .displayPolicy(ContextDisplayPolicy.NAME_ONLY)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build());
        return blocks;
    }

    private int estimateTokens(List<HistoryEntry> entries) {
        if (entries.isEmpty()) return 0;
        String combined = entries.stream()
                .map(HistoryEntry::content)
                .collect(Collectors.joining("\n"));
        return estimator.estimate(combined);
    }


    /** 供非 Spring 构造场景设置触发阈值 */
    public void setSummaryTriggerTokens(int tokens) {
        this.summaryTriggerTokens = tokens;
    }

    public void setSummaryKeepMessages(int count) {
        this.summaryKeepMessages = count;
    }

    private AgentContextSummary latestSummary(ContextRequest request) {
        if (compressionService == null || request == null || request.userId() == null) {
            return null;
        }
        return compressionService.latestConversationSummary(request.userId(), request.conversationId());
    }

    private record HistoryEntry(String role, String content) {
    }
}
