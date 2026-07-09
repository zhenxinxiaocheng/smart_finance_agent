package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class ToolResultCompressor extends AbstractRuleBasedCompressor {

    public ToolResultCompressor(ContextTokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public boolean supports(ContextBlock block) {
        return block != null && block.sourceType() == ContextSourceType.TOOL_RESULT;
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        String content = """
                § COMPRESSED TOOL RESULT
                ref: %s
                summary:
                %s
                next-use:
                - 如需完整明细，按 ref 查看原始工具结果。
                """.formatted(block.storageRef() == null ? "" : block.storageRef(),
                firstLines(block.content(), 8, targetChars(targetTokens)));
        return compressedBlock(block, content.trim());
    }
}
