package com.smartfinance.agent.context;

import java.util.List;

public record ContextUsageSnapshot(int maxTokens,
                                   int reservedOutputTokens,
                                   int usedTokens,
                                   int remainingTokens,
                                   double usageRatio,
                                   List<BlockUsage> blocks,
                                   List<String> droppedKeys,
                                   List<String> compressedKeys) {

    public record BlockUsage(String key,
                             String displayName,
                             ContextSourceType sourceType,
                             int tokens,
                             boolean hidden,
                             boolean compressed) {
    }
}
