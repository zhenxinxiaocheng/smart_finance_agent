package com.smartfinance.agent.agent;

import com.smartfinance.agent.service.SkillInvocationRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

    @Mock
    private FinancialTools financialTools;
    @Mock
    private TransactionRecorder transactionRecorder;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private BudgetTool budgetTool;
    @Mock
    private SkillInvocationRecordService skillInvocationRecordService;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(financialTools, transactionRecorder, webSearchTool, budgetTool,
                skillInvocationRecordService);
    }

    @Test
    void manifest_shouldGroupToolsAsSkills() {
        String manifest = registry.manifest();

        assertThat(manifest).contains("【财务查询】");
        assertThat(manifest).contains("get_total_expense");
        assertThat(manifest).contains("风险等级");
    }

    @Test
    void execute_shouldRecordSkillInvocationWithTraceId() {
        when(financialTools.getTotalExpense(any(), any())).thenReturn("支出 100 元");

        ToolRegistry.ToolObservation observation =
                registry.execute("get_total_expense", null, 1L, "trace-1");

        assertThat(observation.isSuccess()).isTrue();
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-1"),
                eq("get_total_expense"), eq("财务查询"), any(), eq(true), any(), eq("支出 100 元"));
    }
}
