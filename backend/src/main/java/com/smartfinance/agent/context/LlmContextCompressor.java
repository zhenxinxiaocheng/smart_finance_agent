package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class LlmContextCompressor extends AbstractRuleBasedCompressor {

    public LlmContextCompressor(ContextTokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public boolean supports(ContextBlock block) {
        return block != null
                && (block.sourceType() == ContextSourceType.COMPRESSED_SUMMARY
                || block.sourceType() == ContextSourceType.TASK_STATE);
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        String content = """
                § COMPRESSED CONTEXT
                %s
                """.formatted(firstLines(block.content(), 8, targetChars(targetTokens)));
        return compressedBlock(block, content.trim());
    }
}
