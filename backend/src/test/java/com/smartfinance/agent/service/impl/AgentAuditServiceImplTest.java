package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AgentAuditResponse;
import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.mapper.AgentReflectionMapper;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import com.smartfinance.agent.mapper.PendingActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAuditServiceImplTest {

    @Mock
    private AgentReflectionMapper reflectionMapper;
    @Mock
    private PendingActionMapper pendingActionMapper;
    @Mock
    private AgentScheduleMapper scheduleMapper;
    @Mock
    private AgentScheduleRunMapper scheduleRunMapper;

    private AgentAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentAuditServiceImpl(reflectionMapper, pendingActionMapper, scheduleMapper, scheduleRunMapper, new ObjectMapper());
    }

    @Test
    void overview_shouldAggregateSummaryAndStableEvents() {
        AgentReflection openReflection = new AgentReflection();
        openReflection.setId(1L);
        openReflection.setUserId(1L);
        openReflection.setSuggestionType("SCHEDULE_CANDIDATE");
        openReflection.setStatus("OPEN");
        openReflection.setTitle("可沉淀为周期任务");
        openReflection.setTraceId("trace-1");
        openReflection.setCreatedAt(LocalDateTime.parse("2026-07-04T09:00:00"));

        AgentReflection dismissedRisk = new AgentReflection();
        dismissedRisk.setId(2L);
        dismissedRisk.setUserId(1L);
        dismissedRisk.setSuggestionType("RISK_WARNING");
        dismissedRisk.setStatus("DISMISSED");
        dismissedRisk.setTitle("周期任务失败风险");
        dismissedRisk.setCreatedAt(LocalDateTime.parse("2026-07-04T08:00:00"));

        PendingAction pendingAction = new PendingAction();
        pendingAction.setId(3L);
        pendingAction.setUserId(1L);
        pendingAction.setActionType("CREATE_AGENT_SCHEDULE");
        pendingAction.setStatus("PENDING");
        pendingAction.setTitle("确认创建周期任务");
        pendingAction.setPayload("""
                {"sourceTraceId":"trace-1"}
                """);
        pendingAction.setUpdatedAt(LocalDateTime.parse("2026-07-04T10:00:00"));

        PendingAction confirmedAction = new PendingAction();
        confirmedAction.setId(4L);
        confirmedAction.setUserId(1L);
        confirmedAction.setActionType("INSTALL_AGENT_MEMORY");
        confirmedAction.setStatus("CONFIRMED");
        confirmedAction.setTitle("确认写入长期记忆");
        confirmedAction.setPayload("""
                {"resultEntityType":"AGENT_MEMORY","resultEntityId":9}
                """);
        confirmedAction.setUpdatedAt(LocalDateTime.parse("2026-07-04T11:00:00"));

        AgentSchedule failedSchedule = new AgentSchedule();
        failedSchedule.setId(5L);
        failedSchedule.setUserId(1L);
        failedSchedule.setName("每周预算复盘");
        failedSchedule.setEnabled(0);
        failedSchedule.setLastStatus("FAILED");
        failedSchedule.setRunCount(3);
        failedSchedule.setConsecutiveFailures(3);
        failedSchedule.setUpdatedAt(LocalDateTime.parse("2026-07-04T12:00:00"));

        AgentSchedule activeSchedule = new AgentSchedule();
        activeSchedule.setId(6L);
        activeSchedule.setUserId(1L);
        activeSchedule.setName("每日支出检查");
        activeSchedule.setEnabled(1);
        activeSchedule.setLastStatus("SUCCESS");
        activeSchedule.setRunCount(5);
        activeSchedule.setConsecutiveFailures(0);
        activeSchedule.setUpdatedAt(LocalDateTime.parse("2026-07-04T07:00:00"));

        AgentScheduleRun failedRun = new AgentScheduleRun();
        failedRun.setId(7L);
        failedRun.setScheduleId(5L);
        failedRun.setUserId(1L);
        failedRun.setTraceId("trace-run-7");
        failedRun.setStatus("FAILED");
        failedRun.setErrorMessage("LLM timeout");
        failedRun.setStartedAt(LocalDateTime.parse("2026-07-04T12:58:00"));
        failedRun.setFinishedAt(LocalDateTime.parse("2026-07-04T13:00:00"));
        failedRun.setDurationMs(120000L);

        when(reflectionMapper.selectList(any())).thenReturn(List.of(openReflection, dismissedRisk));
        when(pendingActionMapper.selectList(any())).thenReturn(List.of(pendingAction, confirmedAction));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(failedSchedule, activeSchedule));
        when(scheduleRunMapper.selectList(any())).thenReturn(List.of(failedRun));

        AgentAuditResponse response = service.overview(1L);

        assertThat(response.getSummary().getOpenReflections()).isEqualTo(1);
        assertThat(response.getSummary().getPendingActions()).isEqualTo(1);
        assertThat(response.getSummary().getFailedSchedules()).isEqualTo(1);
        assertThat(response.getSummary().getActiveSchedules()).isEqualTo(1);
        assertThat(response.getEvents()).extracting("id")
                .containsExactly("schedule-run-7", "schedule-5", "action-4", "action-3", "reflection-1", "reflection-2", "schedule-6");
        assertThat(response.getEvents().get(0).getSummary()).isEqualTo("执行失败 · LLM timeout");
        assertThat(response.getEvents().get(0).getRawStatus()).isEqualTo("FAILED");
        assertThat(response.getEvents().get(0).getSeverity()).isEqualTo("warning");
        assertThat(response.getEvents().get(0).getTraceId()).isEqualTo("trace-run-7");
        assertThat(response.getEvents().get(0).getTarget().getPath()).isEqualTo("/schedules");
        assertThat(response.getEvents().get(0).getTarget().getQuery()).containsEntry("scheduleId", 5L);
        assertThat(response.getEvents().get(0).getTarget().getQuery()).containsEntry("runId", 7L);
        assertThat(response.getEvents().get(2).getSummary()).isEqualTo("写入长期记忆 · 已确认 -> 长期记忆 #9");
        assertThat(response.getEvents().get(3).getRawStatus()).isEqualTo("PENDING");
        assertThat(response.getEvents().get(3).getTraceId()).isEqualTo("trace-1");
        assertThat(response.getEvents().stream()
                .filter(event -> "reflection-2".equals(event.getId()))
                .findFirst()
                .orElseThrow()
                .getSeverity()).isEqualTo("warning");
    }
}
