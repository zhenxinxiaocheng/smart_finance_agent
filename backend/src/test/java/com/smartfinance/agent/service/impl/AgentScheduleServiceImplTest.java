package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.mapper.AgentScheduleMapper;
import com.smartfinance.agent.mapper.AgentScheduleRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentScheduleServiceImplTest {

    @Mock
    private AgentScheduleMapper scheduleMapper;
    @Mock
    private AgentScheduleRunMapper scheduleRunMapper;
    @Mock
    private AgentScheduleRunner scheduleRunner;

    private AgentScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentScheduleServiceImpl(scheduleMapper, scheduleRunMapper, scheduleRunner);
    }

    @Test
    void create_shouldPersistEnabledScheduleWithNextRun() {
        AgentSchedule schedule = service.create(
                1L,
                "Weekly review",
                "Review spending every Monday",
                "0 0 9 ? * MON",
                "Review my spending and budget risks",
                "Asia/Shanghai"
        );

        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).insert(captor.capture());
        AgentSchedule saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("Weekly review");
        assertThat(saved.getEnabled()).isEqualTo(1);
        assertThat(saved.getRunCount()).isZero();
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getNextRunAt()).isNotNull();
        assertThat(schedule).isSameAs(saved);
    }

    @Test
    void create_shouldNormalizeFiveFieldCron() {
        AgentSchedule schedule = service.create(
                1L,
                "Daily review",
                "Review spending every day",
                "0 10 * * *",
                "Review my spending",
                "Asia/Shanghai"
        );

        ArgumentCaptor<AgentSchedule> captor = ArgumentCaptor.forClass(AgentSchedule.class);
        verify(scheduleMapper).insert(captor.capture());
        AgentSchedule saved = captor.getValue();
        assertThat(saved.getCronExpression()).isEqualTo("0 0 10 * * *");
        assertThat(saved.getNextRunAt()).isNotNull();
        assertThat(schedule).isSameAs(saved);
    }

    @Test
    void update_shouldPersistEditableFieldsAndRecalculateNextRun() {
        AgentSchedule existing = new AgentSchedule();
        existing.setId(8L);
        existing.setUserId(1L);
        existing.setName("Old");
        existing.setDescription("Old description");
        existing.setCronExpression("0 0 9 * * *");
        existing.setTimezone("Asia/Shanghai");
        existing.setTaskQuery("Old task");
        existing.setEnabled(1);
        existing.setDeleted(0);
        when(scheduleMapper.selectById(8L)).thenReturn(existing);

        AgentSchedule updated = service.update(
                1L,
                8L,
                "Daily greeting",
                "Say hello every night",
                "16 20 * * *",
                "Send hello",
                "Asia/Shanghai"
        );

        assertThat(updated).isSameAs(existing);
        assertThat(updated.getName()).isEqualTo("Daily greeting");
        assertThat(updated.getDescription()).isEqualTo("Say hello every night");
        assertThat(updated.getCronExpression()).isEqualTo("0 16 20 * * *");
        assertThat(updated.getTaskQuery()).isEqualTo("Send hello");
        assertThat(updated.getLockUntil()).isNull();
        assertThat(updated.getNextRunAt()).isNotNull();
        verify(scheduleMapper).updateById(existing);
    }

    @Test
    void retryNow_shouldDelegateToRunner() {
        AgentSchedule retried = new AgentSchedule();
        retried.setId(9L);
        retried.setEnabled(1);
        when(scheduleRunner.executeNow(1L, 9L)).thenReturn(retried);

        AgentSchedule result = service.retryNow(1L, 9L);

        assertThat(result).isSameAs(retried);
        verify(scheduleRunner).executeNow(1L, 9L);
    }

    @Test
    void delete_shouldSoftDeleteScheduleToPreserveRunHistory() {
        AgentSchedule existing = new AgentSchedule();
        existing.setId(8L);
        existing.setUserId(1L);
        existing.setEnabled(1);
        existing.setDeleted(0);
        when(scheduleMapper.selectById(8L)).thenReturn(existing);

        service.delete(1L, 8L);

        assertThat(existing.getEnabled()).isZero();
        assertThat(existing.getNextRunAt()).isNull();
        assertThat(existing.getLockUntil()).isNull();
        verify(scheduleMapper).updateById(existing);
        verify(scheduleMapper).deleteById(8L);
    }

}
