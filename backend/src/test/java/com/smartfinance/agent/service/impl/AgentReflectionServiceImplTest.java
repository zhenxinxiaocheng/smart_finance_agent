package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AgentReflectionAcceptRequest;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.mapper.AgentReflectionMapper;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentReflectionServiceImplTest {

    @Mock
    private AgentReflectionMapper reflectionMapper;
    @Mock
    private AgentRunService agentRunService;
    @Mock
    private PendingActionService pendingActionService;

    private AgentReflectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentReflectionServiceImpl(reflectionMapper, agentRunService, pendingActionService, new ObjectMapper());
    }

    @Test
    void reflectRun_shouldCreateRiskWarningForFailedRunEvidence() {
        when(agentRunService.detail(1L, "trace-failed")).thenReturn(Map.of(
                "traceId", "trace-failed",
                "query", "分析本周预算",
                "status", "FAILED",
                "errorMessage", "model unavailable",
                "steps", List.of(Map.of(
                        "tool", "get_total_expense",
                        "status", "failed",
                        "success", false,
                        "errorMessage", "timeout"
                ))
        ));

        List<AgentReflection> reflections = service.reflectRun(1L, "trace-failed");

        assertThat(reflections).hasSize(1);
        AgentReflection reflection = reflections.get(0);
        assertThat(reflection.getSuggestionType()).isEqualTo("RISK_WARNING");
        assertThat(reflection.getStatus()).isEqualTo("OPEN");
        assertThat(reflection.getTitle()).contains("运行失败");
        assertThat(reflection.getSummary()).contains("model unavailable");
        assertThat(reflection.getPayload()).contains("trace-failed");

        ArgumentCaptor<AgentReflection> captor = ArgumentCaptor.forClass(AgentReflection.class);
        verify(reflectionMapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-failed");
    }

    @Test
    void reflectRun_shouldCreateScheduleCandidateForRecurringMonitoringIntent() {
        when(agentRunService.detail(1L, "trace-schedule")).thenReturn(Map.of(
                "traceId", "trace-schedule",
                "query", "以后每天提醒我检查异常支出",
                "status", "COMPLETED",
                "finalAnswer", "可以每天检查。",
                "steps", List.of()
        ));

        List<AgentReflection> reflections = service.reflectRun(1L, "trace-schedule");

        assertThat(reflections).hasSize(1);
        assertThat(reflections.get(0).getSuggestionType()).isEqualTo("SCHEDULE_CANDIDATE");
        assertThat(reflections.get(0).getTitle()).contains("周期任务");
        assertThat(reflections.get(0).getSummary()).contains("每天提醒我检查异常支出");
    }

    @Test
    void reflectRun_shouldCreateSkillCandidateForRepeatedWorkflowIntent() {
        when(agentRunService.detail(1L, "trace-skill")).thenReturn(Map.of(
                "traceId", "trace-skill",
                "query", "以后分析股票都先联网搜索再说明风险",
                "status", "COMPLETED",
                "finalAnswer", "下次会按这个流程做。",
                "steps", List.of()
        ));

        List<AgentReflection> reflections = service.reflectRun(1L, "trace-skill");

        assertThat(reflections).hasSize(1);
        assertThat(reflections.get(0).getSuggestionType()).isEqualTo("SKILL_CANDIDATE");
        assertThat(reflections.get(0).getTitle()).contains("Skill");
        assertThat(reflections.get(0).getSummary()).contains("以后分析股票");
    }

    @Test
    void reflectRun_shouldCreateMemoryCandidateForExplicitPreferenceIntent() {
        when(agentRunService.detail(1L, "trace-memory")).thenReturn(Map.of(
                "traceId", "trace-memory",
                "query", "记住，以后回答都尽量简短一点",
                "status", "COMPLETED",
                "finalAnswer", "好的，我会尽量简短。",
                "steps", List.of()
        ));

        List<AgentReflection> reflections = service.reflectRun(1L, "trace-memory");

        assertThat(reflections).hasSize(1);
        assertThat(reflections.get(0).getSuggestionType()).isEqualTo("MEMORY_CANDIDATE");
        assertThat(reflections.get(0).getTitle()).contains("记忆");
        assertThat(reflections.get(0).getSummary()).contains("以后回答");
    }

    @Test
    void reflectRun_shouldReturnExistingReflectionForSameTraceAndTypeWithoutDuplicateInsert() {
        when(agentRunService.detail(1L, "trace-memory-existing")).thenReturn(Map.of(
                "traceId", "trace-memory-existing",
                "query", "记住，以后回答都尽量简短一点",
                "status", "COMPLETED",
                "finalAnswer", "好的，我会尽量简短。",
                "steps", List.of()
        ));
        AgentReflection existing = new AgentReflection();
        existing.setId(21L);
        existing.setUserId(1L);
        existing.setTraceId("trace-memory-existing");
        existing.setSuggestionType("MEMORY_CANDIDATE");
        existing.setStatus("OPEN");
        when(reflectionMapper.selectOne(any())).thenReturn(existing);

        List<AgentReflection> reflections = service.reflectRun(1L, "trace-memory-existing");

        assertThat(reflections).containsExactly(existing);
        verify(reflectionMapper, never()).insert(any(AgentReflection.class));
    }

    @Test
    void reflectScheduleFailure_shouldCreateRiskWarningWithScheduleEvidence() {
        AgentReflection reflection = service.reflectScheduleFailure(
                1L,
                17L,
                "检查本周预算风险",
                "model unavailable",
                3
        );

        assertThat(reflection.getSuggestionType()).isEqualTo("RISK_WARNING");
        assertThat(reflection.getStatus()).isEqualTo("OPEN");
        assertThat(reflection.getTraceId()).isEqualTo("schedule-17-failure");
        assertThat(reflection.getTitle()).contains("周期任务");
        assertThat(reflection.getSummary()).contains("连续失败 3 次");
        assertThat(reflection.getPayload()).contains("\"scheduleId\":17");
        assertThat(reflection.getPayload()).contains("\"consecutiveFailures\":3");
        assertThat(reflection.getPayload()).contains("model unavailable");
        verify(reflectionMapper).insert(any(AgentReflection.class));
    }

    @Test
    void dismiss_shouldMarkOwnedOpenReflectionDismissed() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(8L);
        reflection.setUserId(1L);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(8L)).thenReturn(reflection);

        AgentReflection dismissed = service.dismiss(1L, 8L);

        assertThat(dismissed.getStatus()).isEqualTo("DISMISSED");
        verify(reflectionMapper).updateById(reflection);
    }

    @Test
    void accept_shouldConvertSkillCandidateToPendingCustomSkillAndMarkAccepted() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(9L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("SKILL_CANDIDATE");
        reflection.setTitle("可沉淀为自定义 Skill");
        reflection.setSummary("这次需求像是一个稳定工作流，可考虑沉淀为 Skill。");
        reflection.setPayload("""
                {"traceId":"trace-skill","query":"以后分析股票都先联网搜索再说明风险"}
                """);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(9L)).thenReturn(reflection);
        PendingAction action = pendingAction(90L, "INSTALL_CUSTOM_SKILL");
        when(pendingActionService.prepareCustomSkill(
                org.mockito.Mockito.eq(1L),
                any(CustomSkillDraftRequest.class),
                org.mockito.Mockito.eq(9L),
                org.mockito.Mockito.eq("trace-skill")
        )).thenReturn(action);

        AgentReflection accepted = service.accept(1L, 9L);

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        assertThat(accepted.getPayload()).contains("\"pendingActionId\":90");
        ArgumentCaptor<CustomSkillDraftRequest> captor = ArgumentCaptor.forClass(CustomSkillDraftRequest.class);
        verify(pendingActionService).prepareCustomSkill(
                org.mockito.Mockito.eq(1L),
                captor.capture(),
                org.mockito.Mockito.eq(9L),
                org.mockito.Mockito.eq("trace-skill")
        );
        assertThat(captor.getValue().getCategory()).isEqualTo("Reflection");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("READ_ONLY");
        assertThat(captor.getValue().getTriggerText()).contains("以后分析股票");
        verify(reflectionMapper).updateById(reflection);
    }

    @Test
    void accept_shouldConvertMemoryCandidateToPendingMemoryAndMarkAccepted() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(11L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("MEMORY_CANDIDATE");
        reflection.setTitle("可沉淀为长期记忆");
        reflection.setSummary("用户偏好：以后回答都尽量简短一点");
        reflection.setPayload("""
                {"traceId":"trace-memory","query":"记住，以后回答都尽量简短一点"}
                """);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(11L)).thenReturn(reflection);
        when(pendingActionService.prepareMemory(
                org.mockito.Mockito.eq(1L),
                any(AgentMemoryRequest.class),
                org.mockito.Mockito.eq(11L),
                org.mockito.Mockito.eq("trace-memory")
        )).thenReturn(pendingAction(91L, "INSTALL_AGENT_MEMORY"));

        AgentReflection accepted = service.accept(1L, 11L);

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        ArgumentCaptor<AgentMemoryRequest> captor = ArgumentCaptor.forClass(AgentMemoryRequest.class);
        verify(pendingActionService).prepareMemory(
                org.mockito.Mockito.eq(1L),
                captor.capture(),
                org.mockito.Mockito.eq(11L),
                org.mockito.Mockito.eq("trace-memory")
        );
        assertThat(captor.getValue().getMemoryType()).isEqualTo("RESPONSE_STYLE");
        assertThat(captor.getValue().getMemoryKey()).isEqualTo("reflection_trace-memory");
        assertThat(captor.getValue().getMemoryValue()).contains("以后回答");
        verify(reflectionMapper).updateById(reflection);
    }

    @Test
    void accept_shouldConvertExplicitDailyScheduleCandidateToPendingScheduleAndMarkAccepted() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(12L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("SCHEDULE_CANDIDATE");
        reflection.setTitle("可沉淀为周期任务");
        reflection.setSummary("这次需求像是一个可重复执行的监控或复盘任务：以后每天提醒我检查异常支出");
        reflection.setPayload("""
                {"traceId":"trace-schedule","query":"以后每天提醒我检查异常支出"}
                """);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(12L)).thenReturn(reflection);
        when(pendingActionService.prepareSchedule(
                org.mockito.Mockito.eq(1L),
                any(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.Mockito.eq(12L),
                org.mockito.Mockito.eq("trace-schedule")
        )).thenReturn(pendingAction(92L, "CREATE_AGENT_SCHEDULE"));

        AgentReflection accepted = service.accept(1L, 12L);

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        verify(pendingActionService).prepareSchedule(
                org.mockito.Mockito.eq(1L),
                org.mockito.Mockito.eq("运行反思周期任务"),
                org.mockito.Mockito.contains("每天提醒我检查异常支出"),
                org.mockito.Mockito.eq("0 0 9 * * ?"),
                org.mockito.Mockito.eq("以后每天提醒我检查异常支出"),
                org.mockito.Mockito.eq("Asia/Shanghai"),
                org.mockito.Mockito.eq(12L),
                org.mockito.Mockito.eq("trace-schedule")
        );
        verify(reflectionMapper).updateById(reflection);
    }

    @Test
    void accept_shouldUseScheduleDraftOverridesWhenProvided() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(14L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("SCHEDULE_CANDIDATE");
        reflection.setTitle("可沉淀为周期任务");
        reflection.setSummary("这次需求像是一个可重复执行的监控或复盘任务：以后定期检查预算风险");
        reflection.setPayload("""
                {"traceId":"trace-schedule-edit","query":"以后定期检查预算风险"}
                """);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(14L)).thenReturn(reflection);
        AgentReflectionAcceptRequest request = new AgentReflectionAcceptRequest();
        request.setName("每周预算风险巡检");
        request.setDescription("每周五下午检查预算风险");
        request.setCronExpression("0 30 17 ? * FRI");
        request.setTaskQuery("检查本周预算风险并给出下周建议");
        request.setTimezone("Asia/Hong_Kong");
        when(pendingActionService.prepareSchedule(
                org.mockito.Mockito.eq(1L),
                any(),
                any(),
                any(),
                any(),
                any(),
                org.mockito.Mockito.eq(14L),
                org.mockito.Mockito.eq("trace-schedule-edit")
        )).thenReturn(pendingAction(94L, "CREATE_AGENT_SCHEDULE"));

        AgentReflection accepted = service.accept(1L, 14L, request);

        assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        verify(pendingActionService).prepareSchedule(
                org.mockito.Mockito.eq(1L),
                org.mockito.Mockito.eq("每周预算风险巡检"),
                org.mockito.Mockito.eq("每周五下午检查预算风险"),
                org.mockito.Mockito.eq("0 30 17 ? * FRI"),
                org.mockito.Mockito.eq("检查本周预算风险并给出下周建议"),
                org.mockito.Mockito.eq("Asia/Hong_Kong"),
                org.mockito.Mockito.eq(14L),
                org.mockito.Mockito.eq("trace-schedule-edit")
        );
        verify(reflectionMapper).updateById(reflection);
    }

    @Test
    void accept_shouldRejectAmbiguousScheduleCandidateWithoutCronInference() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(13L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("SCHEDULE_CANDIDATE");
        reflection.setTitle("可沉淀为周期任务");
        reflection.setSummary("这次需求像是一个可重复执行的监控或复盘任务：以后定期检查预算风险");
        reflection.setPayload("""
                {"traceId":"trace-schedule-ambiguous","query":"以后定期检查预算风险"}
                """);
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(13L)).thenReturn(reflection);

        assertThatThrownBy(() -> service.accept(1L, 13L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schedule frequency is ambiguous");
    }

    @Test
    void accept_shouldRejectRiskWarningBecauseItIsNotAutomaticallyConvertible() {
        AgentReflection reflection = new AgentReflection();
        reflection.setId(10L);
        reflection.setUserId(1L);
        reflection.setSuggestionType("RISK_WARNING");
        reflection.setStatus("OPEN");
        when(reflectionMapper.selectById(10L)).thenReturn(reflection);

        assertThatThrownBy(() -> service.accept(1L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be accepted automatically");
    }

    private static PendingAction pendingAction(Long id, String actionType) {
        PendingAction action = new PendingAction();
        action.setId(id);
        action.setActionType(actionType);
        action.setStatus("PENDING");
        return action;
    }
}
