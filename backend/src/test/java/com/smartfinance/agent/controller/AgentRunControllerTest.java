package com.smartfinance.agent.controller;

import com.smartfinance.agent.entity.SkillInvocationRecord;
import com.smartfinance.agent.service.AgentRunService;
import com.smartfinance.agent.service.SkillInvocationRecordService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunControllerTest {

    @Test
    void detail_shouldReturnRunDetailWithSkillInvocations() {
        AgentRunService agentRunService = mock(AgentRunService.class);
        SkillInvocationRecordService invocationRecordService = mock(SkillInvocationRecordService.class);
        AgentRunController controller = new AgentRunController(agentRunService, invocationRecordService);

        SkillInvocationRecord invocation = new SkillInvocationRecord();
        invocation.setTraceId("trace-1");
        invocation.setSkillName("get_total_expense");
        invocation.setSuccess(1);
        when(agentRunService.detail(1L, "trace-1")).thenReturn(Map.of(
                "traceId", "trace-1",
                "status", "COMPLETED",
                "steps", List.of()
        ));
        when(invocationRecordService.listByTraceId(1L, "trace-1")).thenReturn(List.of(invocation));

        var result = controller.detail(1L, "trace-1");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("traceId", "trace-1");
        assertThat((List<?>) result.getData().get("skillInvocations")).hasSize(1);
    }
}
