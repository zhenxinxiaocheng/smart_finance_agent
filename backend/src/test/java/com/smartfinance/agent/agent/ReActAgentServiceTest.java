package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AgentMemoryPreferencesResponse;
import com.smartfinance.agent.entity.AnalysisRecord;
import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.mapper.AnalysisRecordMapper;
import com.smartfinance.agent.service.AgentMemoryService;
import com.smartfinance.agent.service.FinancialProfileService;
import com.smartfinance.agent.service.RagKnowledgeService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentServiceTest {

    @Mock
    private ChatLanguageModel chatModel;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private AgentVerifier agentVerifier;
    @Mock
    private FinancialMonitor financialMonitor;
    @Mock
    private AnalysisRecordMapper analysisRecordMapper;
    @Mock
    private FinancialProfileService financialProfileService;
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private MemoryExtractor memoryExtractor;
    @Mock
    private RagKnowledgeService ragKnowledgeService;

    private ReActAgentService service;

    @BeforeEach
    void setUp() {
        when(toolRegistry.manifest(1L)).thenReturn("- get_total_expense: test tool");
        when(financialMonitor.hasPendingAlerts(1L)).thenReturn(false);
        lenient().when(financialProfileService.buildAgentContext(1L)).thenReturn("");
        lenient().when(agentMemoryService.buildAgentContext(1L)).thenReturn("");
        lenient().when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("").build());
        lenient().when(agentMemoryService.retrieveRelevantMemories(eq(1L), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(agentMemoryService.isAutoMemoryEnabled(1L)).thenReturn(true);
        lenient().when(agentMemoryService.shouldSkipToolAssistedMemory(1L)).thenReturn(false);
        lenient().when(agentVerifier.verify(any(), any(), anyList()))
                .thenReturn(new AgentVerifier.VerificationResult(true, null, List.of()));
        AgentContextService agentContextService = new AgentContextService(
                financialProfileService, agentMemoryService, ragKnowledgeService);
        service = new ReActAgentService(chatModel, toolRegistry, agentVerifier, financialMonitor,
                analysisRecordMapper, new ObjectMapper(), agentContextService, agentMemoryService, memoryExtractor);
    }

    @Test
    void run_whenModelReturnsAction_shouldExecuteToolAndReturnFinalAnswer() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"查询本月支出","tool":"get_total_expense","input":{"startDate":"2026-06-01","endDate":"2026-06-07"}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"本月目前支出 100 元。"}
                        """));
        when(toolRegistry.execute(eq("get_total_expense"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("支出 100 元")
                        .rawResult("total expense: 100")
                        .build());

        var listener = org.mockito.Mockito.mock(ReActAgentService.ReActEventListener.class);
        var result = service.run(1L, "我这个月花了多少", listener);

        assertEquals("本月目前支出 100 元。", result.getFinalAnswer());
        assertEquals(1, result.getSteps().size());
        assertTrue(result.getSteps().get(0).isSuccess());
        verify(toolRegistry).execute(eq("get_total_expense"), any(), eq(1L), any(), eq(""));
        verify(analysisRecordMapper).insert(any());
        verify(memoryExtractor).enqueue(eq(1L), eq("我这个月花了多少"), any());
        var order = org.mockito.Mockito.inOrder(listener, memoryExtractor);
        order.verify(listener).onFinal(result.getFinalAnswer(), result.getTraceId());
        order.verify(memoryExtractor).enqueue(1L, "我这个月花了多少", result.getFinalAnswer());
    }

    @Test
    void run_shouldPersistTraceIdOnAnalysisRecord() {
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"ok"}
                """));

        var result = service.run(1L, "show my budget");

        ArgumentCaptor<AnalysisRecord> captor = ArgumentCaptor.forClass(AnalysisRecord.class);
        verify(analysisRecordMapper).insert(captor.capture());
        assertEquals(result.getTraceId(), captor.getValue().getTraceId());
    }

    @Test
    void run_whenModelReturnsInvalidJson_shouldAskModelToRepair() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("我来帮你看看"))
                .thenReturn(response("""
                        {"type":"final","answer":"请先补录账单数据。"}
                        """));

        var result = service.run(1L, "看看我的预算");

        assertEquals("请先补录账单数据。", result.getFinalAnswer());
        assertTrue(result.getSteps().isEmpty());
        verify(chatModel, atLeastOnce()).generate(anyList());
    }

    @Test
    void run_whenMaxStepsExceeded_shouldReturnFriendlyIncompleteAnswer() {
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"action","summary":"继续查询","tool":"get_total_expense","input":{}}
                """));
        when(toolRegistry.execute(eq("get_total_expense"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("ok")
                        .rawResult("ok")
                        .build());

        var result = service.run(1L, "做一个很复杂的分析");

        assertEquals(6, result.getSteps().size());
        assertTrue(result.getFinalAnswer().contains("还没有收敛"));
    }

    @Test
    void run_whenToolFails_shouldKeepLoopingWithFailedObservation() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"查询预算","tool":"get_budget_status","input":{}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"预算工具暂时不可用，我稍后再帮你查。"}
                        """));
        when(toolRegistry.execute(eq("get_budget_status"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(false)
                        .summary("工具执行失败")
                        .rawResult("RuntimeException: broken")
                        .build());

        var result = service.run(1L, "看看我的预算有没有超");

        assertFalse(result.getSteps().get(0).isSuccess());
        assertEquals("预算工具暂时不可用，我稍后再帮你查。", result.getFinalAnswer());
    }

    @Test
    void run_whenToolUseGateRequiresTool_shouldForceActionFromToolManifest() {
        when(toolRegistry.manifest(1L)).thenReturn("- record_transaction: 记录一笔收入或支出。input: {userMessage, type, amount, category, description, date}");
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"final","answer":"好的，已为您生成了待确认的记账记录：金额：50.00 元 分类：餐饮 日期：2026-07-09 备注：晚餐。"}
                        """))
                .thenReturn(response("""
                        {"requiresTool":true,"tool":"record_transaction","reason":"最终回答声称生成了待确认记账，需要先调用工具。"}
                        """))
                .thenReturn(response("""
                        {"type":"action","summary":"正在生成待确认记账","tool":"record_transaction","input":{"userMessage":"晚餐花了50","type":"EXPENSE","amount":50,"category":"餐饮","description":"晚餐","date":"2026-07-09"}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"已生成待确认记账，请确认后入账。"}
                        """));
        when(toolRegistry.execute(eq("record_transaction"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("已生成待确认记账，待确认ID：7")
                        .rawResult("已生成待确认记账，待确认ID：7")
                        .build());

        var result = service.run(1L, "晚餐花了50");

        assertEquals("已生成待确认记账，请确认后入账。", result.getFinalAnswer());
        assertEquals(1, result.getSteps().size());
        assertEquals("record_transaction", result.getSteps().get(0).getTool());
        verify(toolRegistry).execute(eq("record_transaction"), any(), eq(1L), any(), eq(""));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, org.mockito.Mockito.times(4)).generate(promptCaptor.capture());
        String gatePrompt = promptCaptor.getAllValues().get(1).toString();
        assertTrue(gatePrompt.contains("工具调用裁决器"));
        assertTrue(gatePrompt.contains("record_transaction"));
        assertTrue(gatePrompt.contains("记录一笔收入或支出"));
    }

    @Test
    void run_shouldKeepRawToolResultOutOfFollowUpPrompt() {
        String rawResult = "RAW_TRANSACTION_ROW_SHOULD_NOT_ENTER_PROMPT";
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"查询交易","tool":"get_transactions","input":{}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"已根据摘要完成分析。"}
                        """));
        when(toolRegistry.execute(eq("get_transactions"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("查询到 2 条交易，总支出 30 元")
                        .rawResult(rawResult)
                        .build());

        service.run(1L, "分析交易");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, org.mockito.Mockito.times(2)).generate(captor.capture());
        String secondPrompt = captor.getAllValues().get(1).toString();
        assertFalse(secondPrompt.contains(rawResult));
        assertTrue(secondPrompt.contains("查询到 2 条交易，总支出 30 元"));
        assertTrue(secondPrompt.contains("完整结果已保存"));
    }

    @Test
    void run_whenActionContainsSkill_shouldPassSkillToToolRegistry() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"按月度复盘技能查询","skill":"monthly-review","tool":"get_monthly_summary","input":{"year":2026,"month":6}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"6 月复盘完成。"}
                        """));
        when(toolRegistry.execute(eq("get_monthly_summary"), any(), eq(1L), any(), eq("monthly-review")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("monthly summary")
                        .rawResult("monthly summary")
                        .build());

        var result = service.run(1L, "按复盘技能看看 6 月");

        assertEquals("6 月复盘完成。", result.getFinalAnswer());
        verify(toolRegistry).execute(eq("get_monthly_summary"), any(), eq(1L), any(), eq("monthly-review"));
    }


    @Test
    void run_withRecentHistory_shouldInjectHistoryAndKeepCurrentMessageOnce() {
        com.smartfinance.agent.entity.ChatMessage userHistory = new com.smartfinance.agent.entity.ChatMessage();
        userHistory.setRole("USER");
        userHistory.setContent("我这个月花了多少");

        com.smartfinance.agent.entity.ChatMessage assistantHistory = new com.smartfinance.agent.entity.ChatMessage();
        assistantHistory.setRole("ASSISTANT");
        assistantHistory.setContent("x".repeat(1100));

        com.smartfinance.agent.entity.ChatMessage emptyHistory = new com.smartfinance.agent.entity.ChatMessage();
        emptyHistory.setRole("USER");
        emptyHistory.setContent("   ");

        com.smartfinance.agent.entity.ChatMessage unknownRole = new com.smartfinance.agent.entity.ChatMessage();
        unknownRole.setRole("SYSTEM");
        unknownRole.setContent("不要注入");

        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"我会结合刚才的支出问题继续分析。"}
                """));

        service.run(1L, "那上个月呢", List.of(userHistory, assistantHistory, emptyHistory, unknownRole));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, atLeastOnce()).generate(captor.capture());

        String joined = captor.getAllValues().get(0).toString();
        assertTrue(joined.contains("历史用户消息"));
        assertTrue(joined.contains("我这个月花了多少"));
        assertTrue(joined.contains("历史助手回复"));
        assertFalse(joined.contains("不要注入"));
        assertEquals(1, countOccurrences(joined, "那上个月呢"));
    }

    @Test
    void run_withFinancialProfile_shouldInjectProfileContext() {
        when(financialProfileService.buildAgentContext(1L))
                .thenReturn("用户长期财务画像：\n风险偏好：保守\n月度总预算目标：1800.00 元");
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"我会按你的保守风险偏好给建议。"}
                """));

        service.run(1L, "给我省钱建议");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, atLeastOnce()).generate(captor.capture());
        String joined = captor.getAllValues().get(0).toString();
        assertTrue(joined.contains("用户主动维护的长期财务画像"));
        assertTrue(joined.contains("风险偏好：保守"));
    }

    @Test
    void run_withAgentMemory_shouldInjectMemoryContext() {
        when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("用中文回答，尽量简短").build());
        when(agentMemoryService.retrieveRelevantMemories(eq(1L), eq("星巴克怎么分类"), anyInt()))
                .thenReturn(List.of(memory("CATEGORY_PREFERENCE", "coffee", "咖啡归为餐饮")));
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"我会按你的分类偏好处理咖啡消费。"}
                """));

        service.run(1L, "星巴克怎么分类");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, atLeastOnce()).generate(captor.capture());
        String joined = captor.getAllValues().get(0).toString();
        assertTrue(joined.contains("Agent 长期指令"));
        assertTrue(joined.contains("咖啡归为餐饮"));
    }

    @Test
    void run_withRelevantRagKnowledge_shouldInjectKnowledgeContext() {
        when(ragKnowledgeService.retrieveRelevantContext("紧急备用金应该准备多少？"))
                .thenReturn("紧急备用金建议覆盖 3-6 个月生活支出。");
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"建议先准备 3-6 个月生活支出作为紧急备用金。"}
                """));

        service.run(1L, "紧急备用金应该准备多少？");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, atLeastOnce()).generate(captor.capture());
        String joined = captor.getAllValues().get(0).toString();
        assertTrue(joined.contains("系统知识库：下面是 RAG 检索到的理财知识片段"));
        assertTrue(joined.contains("紧急备用金建议覆盖 3-6 个月生活支出"));
    }

    @Test
    void run_withLanguageMemory_shouldTellModelToFollowMemoryLanguage() {
        when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("用英语对话").build());
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"Your spending looks stable so far."}
                """));

        service.run(1L, "我这个月消费情况如何？");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, atLeastOnce()).generate(captor.capture());
        String joined = captor.getAllValues().get(0).toString();
        assertTrue(joined.contains("如果当前问题或 Agent 长期记忆指定了其他语言，必须使用指定语言"));
        assertTrue(joined.contains("用英语对话"));
    }

    @Test
    void run_withEnglishMemory_shouldRepairChineseFinalAnswer() {
        when(agentMemoryService.getPreferences(1L))
                .thenReturn(AgentMemoryPreferencesResponse.builder().customInstructions("用英语对话").build());
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"final","answer":"您的本月消费总体正常。"}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"Your monthly spending looks generally normal."}
                        """));

        var result = service.run(1L, "我这个月消费情况如何？");

        assertEquals("Your monthly spending looks generally normal.", result.getFinalAnswer());
    }

    @Test
    void run_whenAutoMemoryDisabled_shouldNotExtractMemory() {
        when(agentMemoryService.isAutoMemoryEnabled(1L)).thenReturn(false);
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"type":"final","answer":"好的"}
                """));

        service.run(1L, "以后回答短一点");

        verify(memoryExtractor, never()).enqueue(any(), any(), any());
    }

    @Test
    void run_whenToolAssistedMemorySkipped_shouldNotExtractMemoryAfterToolCall() {
        when(agentMemoryService.shouldSkipToolAssistedMemory(1L)).thenReturn(true);
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"查询支出","tool":"get_total_expense","input":{}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"已查到"}
                        """));
        when(toolRegistry.execute(eq("get_total_expense"), any(), eq(1L), any(), eq("")))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("ok")
                        .rawResult("ok")
                        .build());

        service.run(1L, "查一下支出");

        verify(memoryExtractor, never()).enqueue(any(), any(), any());
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private Response<AiMessage> response(String text) {
        return Response.from(AiMessage.from(text));
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
