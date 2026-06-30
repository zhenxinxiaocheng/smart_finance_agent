package com.smartfinance.agent.agent;

import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import com.smartfinance.agent.entity.AgentSkill;
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
    private CustomSkillTool customSkillTool;
    @Mock
    private SkillInvocationRecordService skillInvocationRecordService;
    @Mock
    private AgentSkillService agentSkillService;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(financialTools, transactionRecorder, webSearchTool, budgetTool, customSkillTool,
                skillInvocationRecordService, agentSkillService);
    }

    @Test
    void manifest_shouldUseEnabledSkillsFromSkillService() {
        when(agentSkillService.buildEnabledSkillManifest(eq(1L), any())).thenReturn("""
                [Finance Query]
                - get_total_expense: Query total expense. Risk: READ_ONLY input: {}
                """);

        String manifest = registry.manifest(1L);

        assertThat(manifest).contains("Finance Query");
        assertThat(manifest).contains("get_total_expense");
        assertThat(manifest).contains("READ_ONLY");
    }

    @Test
    void execute_shouldRecordSkillInvocationWithTraceId() {
        when(agentSkillService.resolveInvocationSkill(1L, "get_total_expense", null))
                .thenReturn(builtInSkill("get_total_expense", "财务查询", "BUILT_IN", "READ_ONLY", 1));
        when(financialTools.getTotalExpense(any(), any())).thenReturn("expense 100");

        ToolRegistry.ToolObservation observation =
                registry.execute("get_total_expense", null, 1L, "trace-1");

        assertThat(observation.isSuccess()).isTrue();
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-1"),
                eq("get_total_expense"), eq("财务查询"), eq("BUILT_IN"), eq("READ_ONLY"),
                any(), eq(true), eq(false), any(Long.class), any(), eq("expense 100"));
    }

    @Test
    void execute_shouldRejectDisabledSkillAndRecordBlockedInvocation() {
        when(agentSkillService.resolveInvocationSkill(1L, "search_web", null))
                .thenReturn(builtInSkill("search_web", "联网搜索", "BUILT_IN", "EXTERNAL_INFORMATION", 0));

        ToolRegistry.ToolObservation observation =
                registry.execute("search_web", null, 1L, "trace-disabled");

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getSummary()).contains("disabled");
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-disabled"),
                eq("search_web"), eq("联网搜索"), eq("BUILT_IN"), eq("EXTERNAL_INFORMATION"),
                any(), eq(false), eq(true), eq(0L), any(), any());
    }

    @Test
    void execute_withRequestedSkill_shouldRecordInvocationUnderThatSkill() {
        AgentSkill externalSkill = builtInSkill("monthly-review", "财务复盘", "GITHUB", "READ_ONLY", 1);
        when(agentSkillService.resolveInvocationSkill(1L, "get_monthly_summary", "monthly-review"))
                .thenReturn(externalSkill);
        when(financialTools.getMonthlySummary(any(Integer.class), any(Integer.class))).thenReturn("monthly summary");

        ToolRegistry.ToolObservation observation =
                registry.execute("get_monthly_summary", null, 1L, "trace-skill", "monthly-review");

        assertThat(observation.isSuccess()).isTrue();
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-skill"),
                eq("monthly-review"), eq("财务复盘"), eq("GITHUB"), eq("READ_ONLY"),
                any(), eq(true), eq(false), any(Long.class), any(), eq("monthly summary"));
    }

    @Test
    void execute_withSkillNotBoundToTool_shouldBlockAndRecordRejection() {
        when(agentSkillService.resolveInvocationSkill(1L, "search_web", "pdf-helper"))
                .thenThrow(new IllegalArgumentException("Skill pdf-helper is not bound to tool search_web"));

        ToolRegistry.ToolObservation observation =
                registry.execute("search_web", null, 1L, "trace-rejected", "pdf-helper");

        assertThat(observation.isSuccess()).isFalse();
        assertThat(observation.getSummary()).contains("Skill rejected");
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-rejected"),
                eq("pdf-helper"), eq("联网搜索"), eq("UNKNOWN"), eq("EXTERNAL_INFORMATION"),
                any(), eq(false), eq(true), eq(0L), any(), any());
    }

    @Test
    void execute_shouldNormalizeChineseToolAlias() {
        when(agentSkillService.resolveInvocationSkill(1L, "search_web", null))
                .thenReturn(builtInSkill("search_web", "Web Search", "BUILT_IN", "EXTERNAL_INFORMATION", 1));
        when(webSearchTool.searchWeb(any())).thenReturn("market news");

        ToolRegistry.ToolObservation observation =
                registry.execute("\u8054\u7f51\u641c\u7d22", null, 1L, "trace-alias");

        assertThat(observation.isSuccess()).isTrue();
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-alias"),
                eq("search_web"), eq("Web Search"), eq("BUILT_IN"), eq("EXTERNAL_INFORMATION"),
                any(), eq(true), eq(false), any(Long.class), any(), eq("market news"));
    }

    @Test
    void execute_createCustomSkill_shouldCreatePendingActionAndRecordInvocation() {
        when(agentSkillService.resolveInvocationSkill(1L, "create_custom_skill", null))
                .thenReturn(builtInSkill("create_custom_skill", "Skill 管理", "BUILT_IN", "REQUIRES_CONFIRMATION", 1));
        when(customSkillTool.createCustomSkill(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("pending custom skill");

        ToolRegistry.ToolObservation observation =
                registry.execute("create_custom_skill", null, 1L, "trace-custom");

        assertThat(observation.isSuccess()).isTrue();
        verify(skillInvocationRecordService).record(eq(1L), eq("trace-custom"),
                eq("create_custom_skill"), eq("Skill 管理"), eq("BUILT_IN"), eq("REQUIRES_CONFIRMATION"),
                any(), eq(true), eq(false), any(Long.class), any(), eq("pending custom skill"));
    }

    private AgentSkill builtInSkill(String key, String category, String sourceType, String riskLevel, int enabled) {
        AgentSkill skill = new AgentSkill();
        skill.setSkillKey(key);
        skill.setCategory(category);
        skill.setSourceType(sourceType);
        skill.setRiskLevel(riskLevel);
        skill.setEnabled(enabled);
        return skill;
    }
}
