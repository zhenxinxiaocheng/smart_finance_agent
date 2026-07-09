package com.smartfinance.agent.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ContextBudgetService {

    private final ContextTokenEstimator estimator;
    private final List<ContextCompressor> compressors;
    private final ModelContextWindowRegistry modelContextWindowRegistry;
    private final String modelName;

    @Value("${agent.context.max-tokens:12000}")
    private int maxTokens = 12000;

    @Value("${agent.context.reserved-output-tokens:1500}")
    private int reservedOutputTokens = 1500;

    @Value("${agent.context.auto-compress-threshold:0.75}")
    private double autoCompressThreshold = 0.75;

    public ContextBudgetService(ContextTokenEstimator estimator, List<ContextCompressor> compressors) {
        this(estimator, compressors, null, "", 12000, 1500, 0.75);
    }

    @Autowired
    public ContextBudgetService(ContextTokenEstimator estimator,
                                List<ContextCompressor> compressors,
                                ModelContextWindowRegistry modelContextWindowRegistry,
                                @Value("${langchain4j.dashscope.chat-model.model-name:}") String modelName,
                                @Value("${agent.context.max-tokens:12000}") int maxTokens,
                                @Value("${agent.context.reserved-output-tokens:1500}") int reservedOutputTokens,
                                @Value("${agent.context.auto-compress-threshold:0.75}") double autoCompressThreshold) {
        this.estimator = estimator;
        this.compressors = compressors == null ? List.of() : compressors;
        this.modelContextWindowRegistry = modelContextWindowRegistry;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.reservedOutputTokens = reservedOutputTokens;
        this.autoCompressThreshold = autoCompressThreshold;
    }

    public ContextBundle build(ContextRequest request, List<ContextBlock> blocks) {
        List<ContextBlock> candidates = new ArrayList<>((blocks == null ? List.<ContextBlock>of() : blocks).stream()
                .filter(block -> block != null && block.content() != null && !block.content().isBlank())
                .toList());
        candidates.sort(blockComparator());

        int promptBudget = Math.max(1, effectiveMaxTokens() - reservedOutputTokens);
        List<ContextBlock> selected = new ArrayList<>();
        List<ContextBlock> compressed = new ArrayList<>();
        List<ContextBlock> dropped = new ArrayList<>();
        int used = 0;

        for (ContextBlock block : candidates) {
            ContextBlock current = block;
            int tokens = estimator.estimate(current);
            boolean shouldCompress = !current.required()
                    && current.overflowStrategy() == ContextOverflowStrategy.SUMMARIZE
                    && (used + tokens > promptBudget || used + tokens > promptBudget * autoCompressThreshold);
            if (shouldCompress) {
                // 循环压缩：最多尝试3轮，每轮target减半，确保压缩后能放进预算
                ContextBlock compressedBlock = current;
                int targetToken = Math.max(80, promptBudget / 8);
                int remainingBudget = Math.max(1, promptBudget - used);
                for (int round = 0; round < 3; round++) {
                    if (targetToken >= remainingBudget * 2) {
                        targetToken = Math.max(40, remainingBudget / 2);
                    }
                    ContextBlock maybe = compress(compressedBlock, request, targetToken);
                    if (maybe == compressedBlock) break; // 压缩器返回同一对象，说明无法进一步压缩
                    compressedBlock = maybe;
                    int newTokens = estimator.estimate(compressedBlock);
                    if (newTokens <= remainingBudget) {
                        current = compressedBlock;
                        tokens = newTokens;
                        compressed.add(current);
                        break;
                    }
                    targetToken = Math.max(20, targetToken / 2);
                }
                // 3轮压缩后仍然超预算，用压缩结果（至少比原来小）
                if (current == block) {
                    current = compressedBlock;
                    tokens = estimator.estimate(current);
                    compressed.add(current);
                }
            }

            if (used + tokens <= promptBudget || current.required()) {
                selected.add(current);
                used += tokens;
            } else if (current.overflowStrategy() == ContextOverflowStrategy.REFERENCE_ONLY && current.storageRef() != null) {
                ContextBlock ref = referenceOnly(current);
                int refTokens = estimator.estimate(ref);
                if (used + refTokens <= promptBudget) {
                    selected.add(ref);
                    compressed.add(ref);
                    used += refTokens;
                } else {
                    dropped.add(current);
                }
            } else {
                dropped.add(current);
            }
        }

        ContextUsageSnapshot usage = usage(selected, compressed, dropped, used);
        return new ContextBundle(toMessages(selected), usage, List.copyOf(selected), List.copyOf(compressed), List.copyOf(dropped));
    }

    private ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        for (ContextCompressor compressor : compressors) {
            if (compressor.supports(block)) {
                return compressor.compress(block, request, targetTokens);
            }
        }
        return block;
    }

    private ContextBlock referenceOnly(ContextBlock block) {
        return ContextBlock.builder()
                .key(block.key())
                .displayName(block.displayName())
                .sourceType(block.sourceType())
                .content("§ CONTEXT REFERENCE\n完整内容已保存，ref: " + block.storageRef())
                .priority(block.priority())
                .required(block.required())
                .relevanceScore(block.relevanceScore())
                .displayPolicy(block.displayPolicy())
                .overflowStrategy(block.overflowStrategy())
                .storageRef(block.storageRef())
                .compressed(true)
                .updatedAt(block.updatedAt())
                .build();
    }

    private List<ChatMessage> toMessages(List<ContextBlock> selected) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ContextBlock block : selected) {
            if (block.messageRole() == ContextMessageRole.AI) {
                messages.add(AiMessage.from(block.content()));
            } else {
                messages.add(UserMessage.from(block.content()));
            }
        }
        return messages;
    }

    private ContextUsageSnapshot usage(List<ContextBlock> selected,
                                       List<ContextBlock> compressed,
                                       List<ContextBlock> dropped,
                                       int used) {
        List<ContextUsageSnapshot.BlockUsage> blockUsage = selected.stream()
                .map(block -> {
                    boolean hidden = block.displayPolicy() == ContextDisplayPolicy.HIDDEN;
                    return new ContextUsageSnapshot.BlockUsage(
                            hidden ? "hidden" : block.key(),
                            hidden ? "隐藏上下文" : block.displayName(),
                            hidden ? ContextSourceType.SYSTEM : block.sourceType(),
                            estimator.estimate(block),
                            hidden,
                            block.compressed());
                })
                .toList();
        int effectiveMaxTokens = effectiveMaxTokens();
        int promptBudget = Math.max(1, effectiveMaxTokens - reservedOutputTokens);
        return new ContextUsageSnapshot(effectiveMaxTokens, reservedOutputTokens, used,
                Math.max(0, promptBudget - used), Math.min(1.0, used / (double) promptBudget),
                blockUsage,
                dropped.stream().map(ContextBlock::key).toList(),
                compressed.stream().map(ContextBlock::key).toList());
    }

    private Comparator<ContextBlock> blockComparator() {
        return Comparator.comparing(ContextBlock::required).reversed()
                .thenComparing(ContextBlock::priority, Comparator.reverseOrder())
                .thenComparing(ContextBlock::relevanceScore, Comparator.reverseOrder())
                .thenComparing(ContextBlock::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private int effectiveMaxTokens() {
        if (modelContextWindowRegistry == null) {
            return maxTokens;
        }
        return modelContextWindowRegistry.resolve(modelName, maxTokens);
    }
}
