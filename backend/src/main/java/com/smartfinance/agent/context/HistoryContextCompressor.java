package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class HistoryContextCompressor extends AbstractRuleBasedCompressor {

    public HistoryContextCompressor(ContextTokenEstimator estimator) {
        super(estimator);
    }

    @Override
    public boolean supports(ContextBlock block) {
        return block != null && block.sourceType() == ContextSourceType.HISTORY;
    }

    @Override
    public ContextBlock compress(ContextBlock block, ContextRequest request, int targetTokens) {
        // 从尾部（最近对话）开始截取，保留最新的对话轮次
        String recentFacts = lastLines(block.content(), 10, targetChars(targetTokens));
        String content = """
                § 压缩历史
                用户当前问题：%s
                最近对话摘要（从旧到新）：
                %s
                说明：以上为历史压缩片段，按从旧到新排列。如需完整原始对话，请参考 trace 记录。
                """.formatted(
                    request.userMessage() == null ? "" : request.userMessage(),
                    recentFacts.isBlank() ? "- 暂无可压缩历史" : recentFacts
                );
        return compressedBlock(block, content.trim());
    }
}