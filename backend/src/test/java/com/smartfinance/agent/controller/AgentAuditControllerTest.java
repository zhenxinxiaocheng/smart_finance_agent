package com.smartfinance.agent.controller;

import com.smartfinance.agent.dto.AgentAuditResponse;
import com.smartfinance.agent.dto.AgentAuditSummary;
import com.smartfinance.agent.service.AgentAuditService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAuditControllerTest {

    @Test
    void overview_shouldReturnServiceOverview() {
        AgentAuditService service = mock(AgentAuditService.class);
        AgentAuditSummary summary = new AgentAuditSummary();
        summary.setOpenReflections(2);
        AgentAuditResponse response = new AgentAuditResponse();
        response.setSummary(summary);
        response.setEvents(List.of());
        when(service.overview(1L)).thenReturn(response);
        AgentAuditController controller = new AgentAuditController(service);

        var result = controller.overview(1L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getSummary().getOpenReflections()).isEqualTo(2);
        verify(service).overview(1L);
    }
}
