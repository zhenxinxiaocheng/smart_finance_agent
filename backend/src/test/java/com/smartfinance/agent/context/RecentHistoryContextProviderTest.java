package com.smartfinance.agent.context;

import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.service.AgentContextCompressionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentHistoryContextProviderTest {

    @Test
    void provide_shouldUseSummaryAndOnlyUncoveredRecentMessages() {
        AgentContextCompressionService compressionService = mock(AgentContextCompressionService.class);
        AgentContextSummary summary = new AgentContextSummary();
        summary.setSummary("§ MANUAL COMPRESSED CONTEXT\n已总结：旧餐饮预算分析");
        summary.setCoveredUntilMessageId(2L);
        when(compressionService.latestConversationSummary(1L, 7L)).thenReturn(summary);

        RecentHistoryContextProvider provider = new RecentHistoryContextProvider(new ContextTokenEstimator(), compressionService);

        List<ContextBlock> blocks = provider.provide(ContextRequest.builder()
                .userId(1L)
                .conversationId(7L)
                .userMessage("继续")
                .recentHistory(List.of(
                        message(1L, "USER", "旧问题"),
                        message(2L, "ASSISTANT", "旧回答"),
                        message(3L, "USER", "最近问题"),
                        message(4L, "ASSISTANT", "最近回答")
                ))
                .build());

        String joined = blocks.toString();
        assertThat(joined).contains("已总结：旧餐饮预算分析");
        assertThat(joined).contains("最近问题").contains("最近回答");
        assertThat(joined).doesNotContain("旧问题").doesNotContain("旧回答");
    }

    private ChatMessage message(Long id, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
