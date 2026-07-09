package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class RagContextCompressor extends AbstractRuleBasedCompressor {

    public RagContextCompressor(ContextTokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public boolean supports(ContextBlock block) {
        return block != null && block.sourceType() == ContextSourceType.RAG;
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        String content = """
                § COMPRESSED RAG CONTEXT
                当前问题：%s
                相关知识结论：
                %s
                """.formatted(request.userMessage() == null ? "" : request.userMessage(),
                firstLines(block.content(), 6, targetChars(targetTokens)));
        return compressedBlock(block, content.trim());
    }
}
