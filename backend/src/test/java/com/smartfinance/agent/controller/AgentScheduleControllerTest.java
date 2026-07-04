package com.smartfinance.agent.controller;

import com.smartfinance.agent.dto.AgentScheduleRequest;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentScheduleRun;
import com.smartfinance.agent.service.AgentScheduleService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScheduleControllerTest {

    @Test
    void list_shouldReturnUserSchedules() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentSchedule schedule = new AgentSchedule();
        schedule.setName("Weekly review");
        when(service.list(1L)).thenReturn(List.of(schedule));
        AgentScheduleController controller = new AgentScheduleController(service);

        var result = controller.list(1L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getName()).isEqualTo("Weekly review");
    }

    @Test
    void create_shouldPassRequestToService() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentSchedule created = new AgentSchedule();
        created.setId(9L);
        when(service.create(1L, "Weekly review", "Review spending",
                "0 0 9 ? * MON", "Review budget", "Asia/Shanghai"))
                .thenReturn(created);
        AgentScheduleController controller = new AgentScheduleController(service);
        AgentScheduleRequest request = new AgentScheduleRequest();
        request.setName("Weekly review");
        request.setDescription("Review spending");
        request.setCronExpression("0 0 9 ? * MON");
        request.setTaskQuery("Review budget");
        request.setTimezone("Asia/Shanghai");

        var result = controller.create(1L, request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getId()).isEqualTo(9L);
    }

    @Test
    void setEnabled_shouldUpdateScheduleState() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentSchedule schedule = new AgentSchedule();
        schedule.setEnabled(0);
        when(service.setEnabled(1L, 9L, false)).thenReturn(schedule);
        AgentScheduleController controller = new AgentScheduleController(service);

        var result = controller.setEnabled(1L, 9L, false);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getEnabled()).isZero();
    }

    @Test
    void retryNow_shouldExecuteScheduleImmediately() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentSchedule schedule = new AgentSchedule();
        schedule.setId(9L);
        schedule.setEnabled(1);
        schedule.setLastStatus("SUCCESS");
        when(service.retryNow(1L, 9L)).thenReturn(schedule);
        AgentScheduleController controller = new AgentScheduleController(service);

        var result = controller.retryNow(1L, 9L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getLastStatus()).isEqualTo("SUCCESS");
        verify(service).retryNow(1L, 9L);
    }

    @Test
    void delete_shouldDeleteSchedule() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentScheduleController controller = new AgentScheduleController(service);

        var result = controller.delete(1L, 9L);

        assertThat(result.getCode()).isEqualTo(200);
        verify(service).delete(1L, 9L);
    }

    @Test
    void listRuns_shouldReturnScheduleRunHistory() {
        AgentScheduleService service = mock(AgentScheduleService.class);
        AgentScheduleRun run = new AgentScheduleRun();
        run.setScheduleId(9L);
        run.setTraceId("trace-9");
        run.setStatus("SUCCESS");
        when(service.listRuns(1L, 9L)).thenReturn(List.of(run));
        AgentScheduleController controller = new AgentScheduleController(service);

        var result = controller.listRuns(1L, 9L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getTraceId()).isEqualTo("trace-9");
    }
}
