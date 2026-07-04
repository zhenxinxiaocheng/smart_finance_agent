package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.agent.ReActAgentService;
import com.smartfinance.agent.dto.ReActResult;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import com.smartfinance.agent.service.AgentReflectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentScheduleRunnerTest {

    @Mock
    private AgentScheduleMapper scheduleMapper;
    @Mock
    private AgentScheduleRunMapper scheduleRunMapper;
    @Mock
    private ReActAgentService reActAgentService;
    @Mock
    private AgentReflectionService agentReflectionService;

    private AgentScheduleRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AgentScheduleRunner(scheduleMapper, scheduleRunMapper, reActAgentService, agentReflectionService);
    }

    @Test
    void executeDueSchedules_shouldRunReActAndMarkScheduleResult() {
        AgentSchedule due = new AgentSchedule();
        due.setId(7L);
        due.setUserId(1L);
        due.setName("Weekly review");
        due.setCronExpression("0 0 9 ? * MON");
        due.setTimezone("Asia/Shanghai");
        due.setTaskQuery("Review my spending and budget risks");
        due.setRunCount(2);
        due.setConsecutiveFailures(2);
        due.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(1);
        when(reActAgentService.run(1L, "Review my spending and budget risks"))
                .thenReturn(ReActResult.builder()
                        .traceId("trace-7")
                        .finalAnswer("Budget is stable.")
                        .steps(List.of())
                        .build());

        int executed = runner.executeDueSchedules();

        assertThat(executed).isEqualTo(1);
        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        AgentSchedule updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(7L);
        assertThat(updated.getTraceId()).isEqualTo("trace-7");
        assertThat(updated.getRunCount()).isEqualTo(3);
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.getEnabled()).isEqualTo(1);
        assertThat(updated.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastAnswer()).isEqualTo("Budget is stable.");
        assertThat(updated.getLastRunAt()).isNotNull();
        assertThat(updated.getNextRunAt()).isNotNull();

        ArgumentCaptor<AgentScheduleRun> runCaptor = ArgumentCaptor.forClass(AgentScheduleRun.class);
        verify(scheduleRunMapper).insert(runCaptor.capture());
        AgentScheduleRun run = runCaptor.getValue();
        assertThat(run.getScheduleId()).isEqualTo(7L);
        assertThat(run.getUserId()).isEqualTo(1L);
        assertThat(run.getTraceId()).isEqualTo("trace-7");
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(run.getAnswer()).isEqualTo("Budget is stable.");
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getDurationMs()).isNotNull();
    }

    @Test
    void executeDueSchedules_shouldSkipScheduleWhenLockCannotBeAcquired() {
        AgentSchedule due = new AgentSchedule();
        due.setId(8L);
        due.setUserId(1L);
        due.setCronExpression("0 0 9 ? * MON");
        due.setTimezone("Asia/Shanghai");
        due.setTaskQuery("Review my budget");
        due.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(0);

        int executed = runner.executeDueSchedules();

        assertThat(executed).isZero();
        verify(reActAgentService, never()).run(any(), any());
        verify(scheduleMapper, never()).updateById(any());
        verify(scheduleRunMapper, never()).insert(any());
    }

    @Test
    void executeDueSchedules_shouldKeepEnabledAndIncrementFailureCountBeforeCircuitBreakerThreshold() {
        AgentSchedule due = new AgentSchedule();
        due.setId(9L);
        due.setUserId(1L);
        due.setCronExpression("0 0 9 ? * MON");
        due.setTimezone("Asia/Shanghai");
        due.setTaskQuery("Review my budget");
        due.setRunCount(0);
        due.setConsecutiveFailures(1);
        due.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(1);
        when(reActAgentService.run(1L, "Review my budget")).thenThrow(new IllegalStateException("model unavailable"));
        LocalDateTime beforeRun = LocalDateTime.now();

        int executed = runner.executeDueSchedules();

        assertThat(executed).isEqualTo(1);
        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        AgentSchedule updated = captor.getValue();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(2);
        assertThat(updated.getEnabled()).isEqualTo(1);
        assertThat(updated.getNextRunAt()).isNotNull();
        assertThat(updated.getNextRunAt()).isAfterOrEqualTo(beforeRun.plusMinutes(59));
        assertThat(updated.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now().plusMinutes(61));
        assertThat(updated.getLastStatus()).isEqualTo("FAILED");
        assertThat(updated.getLastAnswer()).isEqualTo("model unavailable");
        verify(agentReflectionService).reflectScheduleFailure(
                1L,
                9L,
                "Review my budget",
                "model unavailable",
                2
        );
    }

    @Test
    void executeDueSchedules_shouldRetryAfterShortBackoffOnFirstFailure() {
        AgentSchedule due = new AgentSchedule();
        due.setId(11L);
        due.setUserId(1L);
        due.setCronExpression("0 0 9 ? * MON");
        due.setTimezone("Asia/Shanghai");
        due.setTaskQuery("Review my budget");
        due.setRunCount(0);
        due.setConsecutiveFailures(0);
        due.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(1);
        when(reActAgentService.run(1L, "Review my budget")).thenThrow(new IllegalStateException("model unavailable"));
        LocalDateTime beforeRun = LocalDateTime.now();

        int executed = runner.executeDueSchedules();

        assertThat(executed).isEqualTo(1);
        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        AgentSchedule updated = captor.getValue();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(1);
        assertThat(updated.getEnabled()).isEqualTo(1);
        assertThat(updated.getNextRunAt()).isAfterOrEqualTo(beforeRun.plusMinutes(14));
        assertThat(updated.getNextRunAt()).isBeforeOrEqualTo(LocalDateTime.now().plusMinutes(16));
        assertThat(updated.getLastStatus()).isEqualTo("FAILED");
        verify(agentReflectionService).reflectScheduleFailure(
                1L,
                11L,
                "Review my budget",
                "model unavailable",
                1
        );
    }

    @Test
    void executeDueSchedules_shouldDisableScheduleAfterThreeConsecutiveFailures() {
        AgentSchedule due = new AgentSchedule();
        due.setId(10L);
        due.setUserId(1L);
        due.setCronExpression("0 0 9 ? * MON");
        due.setTimezone("Asia/Shanghai");
        due.setTaskQuery("Review my budget");
        due.setRunCount(2);
        due.setConsecutiveFailures(2);
        due.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        when(scheduleMapper.selectList(any())).thenReturn(List.of(due));
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(1);
        when(reActAgentService.run(1L, "Review my budget")).thenThrow(new IllegalStateException("model unavailable"));

        int executed = runner.executeDueSchedules();

        assertThat(executed).isEqualTo(1);
        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        AgentSchedule updated = captor.getValue();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(3);
        assertThat(updated.getEnabled()).isZero();
        assertThat(updated.getNextRunAt()).isNull();
        assertThat(updated.getLastStatus()).isEqualTo("FAILED");
        verify(agentReflectionService).reflectScheduleFailure(
                1L,
                10L,
                "Review my budget",
                "model unavailable",
                3
        );
    }

    @Test
    void executeNow_shouldRecoverCircuitBrokenScheduleAndRunImmediately() {
        AgentSchedule schedule = new AgentSchedule();
        schedule.setId(12L);
        schedule.setUserId(1L);
        schedule.setCronExpression("0 0 9 ? * MON");
        schedule.setTimezone("Asia/Shanghai");
        schedule.setTaskQuery("Review my budget");
        schedule.setRunCount(3);
        schedule.setConsecutiveFailures(3);
        schedule.setEnabled(0);
        schedule.setDeleted(0);
        schedule.setNextRunAt(null);
        when(scheduleMapper.selectById(12L)).thenReturn(schedule);
        when(scheduleMapper.update(any(AgentSchedule.class), any())).thenReturn(1);
        when(reActAgentService.run(1L, "Review my budget"))
                .thenReturn(ReActResult.builder()
                        .traceId("trace-retry")
                        .finalAnswer("Recovered schedule run.")
                        .steps(List.of())
                        .build());

        AgentSchedule result = runner.executeNow(1L, 12L);

        assertThat(result).isSameAs(schedule);
        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).updateById(captor.capture());
        AgentSchedule updated = captor.getValue();
        assertThat(updated.getEnabled()).isEqualTo(1);
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.getRunCount()).isEqualTo(4);
        assertThat(updated.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getTraceId()).isEqualTo("trace-retry");
        assertThat(updated.getLastAnswer()).isEqualTo("Recovered schedule run.");
        assertThat(updated.getNextRunAt()).isNotNull();
        verify(scheduleRunMapper).insert(any(AgentScheduleRun.class));
        verify(agentReflectionService, never()).reflectScheduleFailure(any(), any(), any(), any(), any());
    }
}
