package com.smartfinance.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetServiceTest {

    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    @Test
    void build_shouldKeepRequiredBlocksAndCompressOptionalBlocksWhenOverBudget() {
        ContextBudgetService service = newService(520, 20, 0.5,
                List.of(new HistoryContextCompressor(estimator), new RagContextCompressor(estimator)));

        ContextBlock required = ContextBlock.builder()
                .key("language")
                .sourceType(ContextSourceType.LANGUAGE)
                .content("系统语言要求：最终 answer 必须使用中文。")
                .priority(100)
                .required(true)
                .relevanceScore(1.0)
                .displayPolicy(ContextDisplayPolicy.VISIBLE)
                .overflowStrategy(ContextOverflowStrategy.KEEP)
                .build();
        ContextBlock history = ContextBlock.builder()
                .key("history")
                .sourceType(ContextSourceType.HISTORY)
                .content("用户目标：分析本月预算。\n已完成步骤：查询本月餐饮支出。\n失败尝试：第一次预算查询超时。\n下一步计划：继续比较上月支出。\n".repeat(20))
                .priority(70)
                .required(false)
                .relevanceScore(0.8)
                .displayPolicy(ContextDisplayPolicy.VISIBLE)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build();

        ContextBundle bundle = service.build(ContextRequest.builder()
                .userId(1L)
                .userMessage("继续")
                .build(), List.of(history, required));

        assertThat(bundle.selectedBlocks()).extracting(ContextBlock::key).contains("language");
        assertThat(bundle.compressedBlocks()).extracting(ContextBlock::key).contains("history");
        assertThat(bundle.usage().usedTokens()).isLessThanOrEqualTo(500);
        assertThat(bundle.messages().toString()).contains("系统语言要求").contains("§ 压缩历史");
    }

    @Test
    void usage_shouldHideBlockDetailsWhenDisplayPolicyIsHidden() {
        ContextBudgetService service = newService(1000, 100, 0.75, List.of());

        ContextBundle bundle = service.build(ContextRequest.builder()
                .userId(1L)
                .userMessage("预算")
                .build(), List.of(ContextBlock.builder()
                .key("profile")
                .sourceType(ContextSourceType.PROFILE)
                .content("月收入 10000，预算 3000")
                .priority(80)
                .required(false)
                .displayPolicy(ContextDisplayPolicy.HIDDEN)
                .overflowStrategy(ContextOverflowStrategy.KEEP)
                .build()));

        assertThat(bundle.usage().blocks()).hasSize(1);
        assertThat(bundle.usage().blocks().get(0).key()).isEqualTo("hidden");
        assertThat(bundle.usage().blocks().get(0).displayName()).isEqualTo("隐藏上下文");
        assertThat(bundle.usage().blocks().get(0).tokens()).isGreaterThan(0);
    }

    @Test
    void compress_shouldPreserveLatestHistoryAsContextForModel() {
        ContextBudgetService service = newService(400, 30, 0.35,
                List.of(new HistoryContextCompressor(estimator)));

        StringBuilder historyContent = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            historyContent.append("旧 USER: 帮我查查第").append(i).append("周的开销\n");
            historyContent.append("旧 ASSISTANT: 第").append(i)
                    .append("周开销").append(i * 100).append("元，其中餐饮占比最高\n");
        }
        historyContent.append("旧 USER: 帮我看看餐饮支出有没有超标\n");
        historyContent.append("旧 ASSISTANT: 本月餐饮预算3000，已支出2850，还有150余额\n");

        ContextBlock history = ContextBlock.builder()
                .key("older-history")
                .sourceType(ContextSourceType.HISTORY)
                .content(historyContent.toString())
                .priority(42)
                .required(false)
                .relevanceScore(0.5)
                .displayPolicy(ContextDisplayPolicy.VISIBLE)
                .overflowStrategy(ContextOverflowStrategy.SUMMARIZE)
                .build();

        ContextBundle bundle = service.build(ContextRequest.builder()
                .userId(1L)
                .userMessage("继续")
                .build(), List.of(history));

        String messagesStr = bundle.messages().toString();
        assertThat(messagesStr).contains("§ 压缩历史");
        
        assertThat(messagesStr).contains("第8周");
        assertThat(messagesStr).contains("第8周");
        assertThat(bundle.usage().usedTokens()).isLessThanOrEqualTo(370);
        assertThat(bundle.usage().compressedKeys()).contains("older-history");
    }

    private ContextBudgetService newService(int maxTokens,
                                            int reservedOutputTokens,
                                            double autoCompressThreshold,
                                            List<ContextCompressor> compressors) {
        return new ContextBudgetService(estimator, compressors, null, "",
                maxTokens, reservedOutputTokens, autoCompressThreshold);
    }
}
