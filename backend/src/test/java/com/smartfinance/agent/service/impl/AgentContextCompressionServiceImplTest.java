package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.AgentContextSummary;
import com.smartfinance.agent.entity.ChatMessage;
import com.smartfinance.agent.mapper.AgentContextSummaryMapper;
import com.smartfinance.agent.mapper.ChatMessageMapper;
import com.smartfinance.agent.context.ContextSummaryGenerator;
import com.smartfinance.agent.context.ContextTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentContextCompressionServiceImplTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private AgentContextSummaryMapper summaryMapper;
    @Mock
    private ContextSummaryGenerator summaryGenerator;

    private AgentContextCompressionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentContextCompressionServiceImpl(chatMessageMapper, summaryMapper,
                new ContextTokenEstimator(), summaryGenerator);
    }

    @Test
    void compressConversation_shouldPersistConversationSummaryWithoutDeletingHistory() {
        ChatMessage first = message("USER", "帮我分析本月餐饮预算");
        first.setId(11L);
        ChatMessage second = message("ASSISTANT", "已完成步骤：查询餐饮支出。下一步计划：对比上月。");
        second.setId(12L);
        when(chatMessageMapper.selectByTraceOrRecentAndConversation(1L, 7L, "trace-1", 30))
                .thenReturn(List.of(first, second));
        when(summaryGenerator.summarize(eq("对话历史"), eq("手动压缩当前对话"),
                contains("帮我分析本月餐饮预算"), eq(800)))
                .thenReturn("统一摘要：用户目标是分析餐饮预算，已完成查询，下一步对比上月。");
        when(summaryGenerator.sourceHash(contains("帮我分析本月餐饮预算"))).thenReturn("hash-1");

        AgentContextSummary summary = service.compressConversation(1L, 7L, "trace-1", "CONVERSATION");

        ArgumentCaptor<AgentContextSummary> captor = ArgumentCaptor.forClass(AgentContextSummary.class);
        verify(summaryMapper).insert(captor.capture());
        assertThat(summary.getSummary()).isEqualTo("统一摘要：用户目标是分析餐饮预算，已完成查询，下一步对比上月。");
        assertThat(summary.getConversationId()).isEqualTo(7L);
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().getSourceHash()).isNotBlank();
        assertThat(captor.getValue().getCoveredFromMessageId()).isEqualTo(11L);
        assertThat(captor.getValue().getCoveredUntilMessageId()).isEqualTo(12L);
        verify(chatMessageMapper).selectByTraceOrRecentAndConversation(1L, 7L, "trace-1", 30);
        verify(summaryGenerator).summarize(eq("对话历史"), eq("手动压缩当前对话"),
                contains("帮我分析本月餐饮预算"), eq(800));
        verify(summaryMapper).insert(any());
    }

    @Test
    void compressConversation_shouldRejectMissingConversationId() {
        assertThatThrownBy(() -> service.compressConversation(1L, "trace-1", "CONVERSATION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conversationId is required");

        verify(chatMessageMapper, never()).selectByTraceOrRecent(1L, "trace-1", 30);
        verify(summaryMapper, never()).insert(any());
    }

    @Test
    void compressConversation_shouldLimitHistoryToConversationWhenConversationIdPresent() {
        ChatMessage message = message("USER", "conversation scoped question");
        message.setId(21L);
        when(chatMessageMapper.selectByTraceOrRecentAndConversation(1L, 7L, "trace-7", 30))
                .thenReturn(List.of(message));
        when(summaryGenerator.summarize(anyString(), anyString(),
                contains("conversation scoped question"), eq(800)))
                .thenReturn("conversation scoped summary");
        when(summaryGenerator.sourceHash(contains("conversation scoped question"))).thenReturn("hash-7");

        AgentContextSummary summary = service.compressConversation(1L, 7L, "trace-7", "CONVERSATION");

        assertThat(summary.getSummary()).isEqualTo("conversation scoped summary");
        assertThat(summary.getConversationId()).isEqualTo(7L);
        ArgumentCaptor<AgentContextSummary> captor = ArgumentCaptor.forClass(AgentContextSummary.class);
        verify(summaryMapper).insert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo(7L);
        verify(chatMessageMapper).selectByTraceOrRecentAndConversation(1L, 7L, "trace-7", 30);
        verify(chatMessageMapper, never()).selectByTraceOrRecent(1L, "trace-7", 30);
    }

    @Test
    void compressConversation_shouldRejectEmptyConversationWithoutPersistingSummary() {
        when(chatMessageMapper.selectByTraceOrRecentAndConversation(1L, 7L, null, 30))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.compressConversation(1L, 7L, null, "CONVERSATION"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("当前对话暂无可压缩内容");

        verify(summaryGenerator, never()).summarize(anyString(), anyString(), anyString(), eq(800));
        verify(summaryMapper, never()).insert(any());
    }

    private ChatMessage message(String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(1L);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId("trace-1");
        return message;
    }
}
