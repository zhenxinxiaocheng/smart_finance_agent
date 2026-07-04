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
    void retryNow_shouldDelegateToRunner() {
        AgentSchedule retried = new AgentSchedule();
        retried.setId(9L);
        retried.setEnabled(1);
        when(scheduleRunner.executeNow(1L, 9L)).thenReturn(retried);

        AgentSchedule result = service.retryNow(1L, 9L);

        assertThat(result).isSameAs(retried);
        verify(scheduleRunner).executeNow(1L, 9L);
    }

}
