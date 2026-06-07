package com.smartfinance.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.mapper.AnalysisRecordMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
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

    private ReActAgentService service;

    @BeforeEach
    void setUp() {
        when(toolRegistry.manifest()).thenReturn("- get_total_expense: test tool");
        when(financialMonitor.hasPendingAlerts(1L)).thenReturn(false);
        lenient().when(agentVerifier.verify(any(), any(), anyList()))
                .thenReturn(new AgentVerifier.VerificationResult(true, null, List.of()));
        service = new ReActAgentService(chatModel, toolRegistry, agentVerifier, financialMonitor,
                analysisRecordMapper, new ObjectMapper());
    }

    @Test
    void run_whenModelReturnsAction_shouldExecuteToolAndReturnFinalAnswer() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("""
                        {"type":"action","summary":"正在查询本月支出","tool":"get_total_expense","input":{"startDate":"2026-06-01","endDate":"2026-06-07"}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"本月目前支出 100 元。"}
                        """));
        when(toolRegistry.execute(eq("get_total_expense"), any(), eq(1L)))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(true)
                        .summary("支出 100 元")
                        .rawResult("total expense: 100")
                        .build());

        var result = service.run(1L, "我这个月花了多少");

        assertEquals("本月目前支出 100 元。", result.getFinalAnswer());
        assertEquals(1, result.getSteps().size());
        assertTrue(result.getSteps().get(0).isSuccess());
        verify(toolRegistry).execute(eq("get_total_expense"), any(), eq(1L));
        verify(analysisRecordMapper).insert(any());
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
        when(toolRegistry.execute(eq("get_total_expense"), any(), eq(1L)))
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
                        {"type":"action","summary":"正在查询预算","tool":"get_budget_status","input":{}}
                        """))
                .thenReturn(response("""
                        {"type":"final","answer":"预算工具暂时不可用，我稍后再帮你查。"}
                        """));
        when(toolRegistry.execute(eq("get_budget_status"), any(), eq(1L)))
                .thenReturn(ToolRegistry.ToolObservation.builder()
                        .success(false)
                        .summary("工具执行失败")
                        .rawResult("RuntimeException: broken")
                        .build());

        var result = service.run(1L, "看看我的预算有没有超");

        assertFalse(result.getSteps().get(0).isSuccess());
        assertEquals("预算工具暂时不可用，我稍后再帮你查。", result.getFinalAnswer());
    }

    private Response<AiMessage> response(String text) {
        return Response.from(AiMessage.from(text));
    }
}
