package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class MemoryContextCompressor extends AbstractRuleBasedCompressor {

    public MemoryContextCompressor(ContextTokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public boolean supports(ContextBlock block) {
        return block != null && block.sourceType() == ContextSourceType.MEMORY;
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        String content = """
                § COMPRESSED MEMORY
                仅保留与当前问题相关的高置信偏好：
                %s
                """.formatted(firstLines(block.content(), 8, targetChars(targetTokens)));
        return compressedBlock(block, content.trim());
    }
}
