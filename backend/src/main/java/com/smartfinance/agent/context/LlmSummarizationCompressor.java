package com.smartfinance.agent.context;

import com.smartfinance.agent.service.AgentContextCompressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LlmSummarizationCompressor implements ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(LlmSummarizationCompressor.class);

    private final AgentContextCompressionService compressionService;
    private final ContextTokenEstimator estimator;
    private final ContextSummaryGenerator summaryGenerator;

    public LlmSummarizationCompressor(AgentContextCompressionService compressionService,
                                      ContextTokenEstimator estimator,
                                      ContextSummaryGenerator summaryGenerator) {
        this.compressionService = compressionService;
        this.estimator = estimator;
        this.summaryGenerator = summaryGenerator;
    }

    @Override
    public boolean supports(ContextBlock block) {
        if (block == null) return false;
        return block.sourceType() == ContextSourceType.HISTORY
                || block.sourceType() == ContextSourceType.RAG
                || block.sourceType() == ContextSourceType.MEMORY
                || block.sourceType() == ContextSourceType.COMPRESSED_SUMMARY;
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        String rawContent = block.content();
        if (rawContent == null || rawContent.isBlank()) {
            return block;
        }

        Long userId = request.userId();
        String scopePrefix = "AUTO_" + block.sourceType().name();
        String sourceHash = summaryGenerator.sourceHash(rawContent);

        // 检查是否有已有的自动摘要缓存
        if (userId != null) {
            var cached = compressionService.latestByScopeAndSourceHash(
                    userId, request.conversationId(), scopePrefix, sourceHash);
            if (cached != null && cached.getSummary() != null && !cached.getSummary().isBlank()) {
                log.debug("Reusing cached auto summary for userId={} scope={} hash={}", userId, scopePrefix, sourceHash);
                return ContextBlock.builder()
                        .key(block.key())
                        .displayName(block.displayName() + "(缓存摘要)")
                        .sourceType(block.sourceType())
                        .content(cached.getSummary())
                        .priority(block.priority())
                        .required(block.required())
                        .relevanceScore(block.relevanceScore())
                        .displayPolicy(block.displayPolicy())
                        .overflowStrategy(block.overflowStrategy())
                        .storageRef("auto-summary:" + cached.getId())
                        .messageRole(block.messageRole())
                        .compressed(true)
                        .updatedAt(cached.getUpdatedAt())
                        .build();
            }
        }

        String sourceLabel = switch (block.sourceType()) {
            case HISTORY -> "对话历史";
            case RAG -> "检索文档";
            case MEMORY -> "长期记忆";
            case COMPRESSED_SUMMARY -> "已有压缩摘要";
            default -> "上下文";
        };

        String summary = summaryGenerator.summarize(sourceLabel, request.userMessage(), rawContent, targetTokens);

        if (userId != null && summary.length() > 20) {
            try {
                compressionService.saveAutoSummary(
                        userId, request.conversationId(), block.sourceType().name(), summary.trim(),
                        block.storageRef(), sourceHash,
                        estimator.estimate(rawContent),
                        estimator.estimate(summary)
                );
            } catch (Exception e) {
                log.warn("Failed to cache auto summary: {}", e.getMessage());
            }
        }

        return ContextBlock.builder()
                .key(block.key())
                .displayName(block.displayName() + "(LLM摘要)")
                .sourceType(block.sourceType())
                .content(summary.trim())
                .priority(block.priority())
                .required(block.required())
                .relevanceScore(block.relevanceScore())
                .displayPolicy(block.displayPolicy())
                .overflowStrategy(block.overflowStrategy())
                .storageRef(block.storageRef())
                .messageRole(block.messageRole())
                .compressed(true)
                .updatedAt(block.updatedAt())
                .build();
    }
}
