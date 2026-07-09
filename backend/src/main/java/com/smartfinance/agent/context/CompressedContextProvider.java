package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.service.AgentContextCompressionService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompressedContextProvider implements AgentContextProvider {

    private final AgentContextCompressionService compressionService;

    public CompressedContextProvider(AgentContextCompressionService compressionService) {
        this.compressionService = compressionService;
    }

    @Override
    public List<ContextBlock> provide(ContextRequest request) {
        AgentContextSummary summary = compressionService.latestByScope(request.userId(), "TASK_STATE");
        if (summary == null || summary.getSummary() == null || summary.getSummary().isBlank()) {
            return List.of();
        }
        return List.of(ContextBlock.builder()
                .key("compressed-summary")
                .displayName("压缩任务摘要")
                .sourceType(ContextSourceType.COMPRESSED_SUMMARY)
                .content(summary.getSummary())
                .priority(82)
                .required(false)
                .relevanceScore(0.75)
                .displayPolicy(ContextDisplayPolicy.NAME_ONLY)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .storageRef(summary.getTraceId())
                .updatedAt(summary.getUpdatedAt())
                .build());
    }
}
