package com.smartfinance.agent.agent;

import com.smartfinance.agent.service.AgentMemoryService;
import com.smartfinance.agent.service.FinancialProfileService;
import com.smartfinance.agent.service.RagKnowledgeService;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.entity.AgentMemory;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentContextServiceTest {

    @Mock
    private FinancialProfileService financialProfileService;
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private RagKnowledgeService ragKnowledgeService;

    private AgentContextService service;

    @BeforeEach
    void setUp() {
        service = new AgentContextService(financialProfileService, agentMemoryService, ragKnowledgeService);
        org.mockito.Mockito.lenient().when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("").build());
        org.mockito.Mockito.lenient().when(agentMemoryService.retrieveRelevantMemories(org.mockito.Mockito.eq(1L), org.mockito.Mockito.any(), org.mockito.Mockito.anyInt()))
                .thenReturn(java.util.List.of());
    }

    @Test
    void build_shouldLayerProfileMemoryRagAndLanguageInstruction() {
        when(financialProfileService.buildAgentContext(1L))
                .thenReturn("用户长期财务画像：\n风险偏好：稳健");
        when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("用英语回答").build());
        when(ragKnowledgeService.retrieveRelevantContext("请用中文解释预算"))
                .thenReturn("预算知识片段");

        AgentContextService.AgentContext context = service.build(1L, "请用中文解释预算");

        assertThat(context.memoryContext()).contains("USER PROFILE");
        assertThat(context.languageInstruction()).isEqualTo("系统语言要求：最终 answer 必须使用中文。");
        String joined = context.messages().toString();
        assertThat(joined).contains("长期财务画像").contains("风险偏好：稳健");
        assertThat(joined).contains("Agent 长期指令").contains("USER PROFILE");
        assertThat(joined).contains("RAG 检索").contains("预算知识片段");
        assertThat(joined).contains("最终 answer 必须使用中文");
    }

    @Test
    void build_shouldUseMemoryLanguageWhenCurrentQuestionDoesNotOverrideIt() {
        when(financialProfileService.buildAgentContext(1L)).thenReturn("");
        when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("用英语回答").build());
        when(ragKnowledgeService.retrieveRelevantContext("分析本月预算"))
                .thenReturn("");

        AgentContextService.AgentContext context = service.build(1L, "分析本月预算");

        assertThat(context.languageInstruction())
                .isEqualTo("System language requirement: the final answer must be written in English. Keep all facts, amounts, and dates unchanged.");
        assertThat(context.messages().toString()).contains("final answer must be written in English");
    }

    @Test
    void build_withRecentHistory_shouldInjectHistoryAndTruncateLongMessages() {
        when(financialProfileService.buildAgentContext(1L)).thenReturn("");
        when(ragKnowledgeService.retrieveRelevantContext("那上个月呢")).thenReturn("");

        com.smartfinance.agent.entity.ChatMessage userHistory = history("USER", "我这个月花了多少");
        com.smartfinance.agent.entity.ChatMessage assistantHistory = history("ASSISTANT", "x".repeat(1100));
        com.smartfinance.agent.entity.ChatMessage emptyHistory = history("USER", "   ");
        com.smartfinance.agent.entity.ChatMessage unknownRole = history("SYSTEM", "不要注入");

        AgentContextService.AgentContext context = service.build(
                1L, "那上个月呢", java.util.List.of(userHistory, assistantHistory, emptyHistory, unknownRole));

        String joined = context.messages().toString();
        assertThat(joined).contains("下面是该用户最近的对话历史");
        assertThat(joined).contains("历史用户消息：我这个月花了多少");
        assertThat(joined).contains("历史助手回复：");
        assertThat(joined).doesNotContain("不要注入");
    }

    @Test
    void build_withLongRecentHistory_shouldKeepOlderHistoryBlockAndProtectLatestMessages() {
        when(financialProfileService.buildAgentContext(1L)).thenReturn("");
        when(ragKnowledgeService.retrieveRelevantContext("继续分析")).thenReturn("");

        AgentContextService.AgentContext context = service.build(1L, "继续分析", java.util.List.of(
                history("USER", "第1轮用户很久以前的问题"),
                history("ASSISTANT", "第1轮助手很久以前的回答"),
                history("USER", "第2轮用户旧问题"),
                history("ASSISTANT", "第2轮助手旧回答"),
                history("USER", "最近用户问题"),
                history("ASSISTANT", "最近助手回答")
        ));

        String joined = context.messages().toString();
        assertThat(joined).contains("旧 USER: 第1轮用户很久以前的问题");
        assertThat(joined).contains("旧 ASSISTANT: 第1轮助手很久以前的回答");
        assertThat(joined).contains("历史助手回复：第2轮助手旧回答");
        assertThat(joined).contains("历史用户消息：最近用户问题");
        assertThat(joined).contains("历史助手回复：最近助手回答");
    }

    private static class UserText {
        String user;
        String assistant;
        UserText(String user, String assistant) { this.user = user; this.assistant = assistant; }
    }
    private UserText generateLongMessage(String base, int extraChars) {
        StringBuilder user = new StringBuilder(base);
        StringBuilder assistant = new StringBuilder(base + "回复");
        // 重复填充字符达到目标长度
        while (user.length() < extraChars) user.append("额外填充分析数据");
        while (assistant.length() < extraChars) assistant.append("填充详细回复内容");
        return new UserText(truncate(user.toString(), extraChars), truncate(assistant.toString(), extraChars));
    }
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
    private String asUserText(AgentContextService.AgentContext context, int index) {
        return ((UserMessage) context.messages().get(index)).singleText();
    }

    private com.smartfinance.agent.entity.ChatMessage history(String role, String content) {
        com.smartfinance.agent.entity.ChatMessage message = new com.smartfinance.agent.entity.ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private AgentMemory memory(String type, String key, String value) {
        AgentMemory memory = new AgentMemory();
        memory.setUserId(1L);
        memory.setMemoryType(type);
        memory.setMemoryKey(key);
        memory.setMemoryValue(value);
        memory.setConfidence(0.9);
        memory.setDisabled(0);
        return memory;
    }
}
