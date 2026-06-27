package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.AgentRun;
import com.smartfinance.agent.entity.AgentRunStep;
import com.smartfinance.agent.mapper.AgentRunMapper;
import com.smartfinance.agent.mapper.AgentRunStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceImplTest {

    @Mock
    private AgentRunMapper agentRunMapper;
    @Mock
    private AgentRunStepMapper agentRunStepMapper;

    private AgentRunServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentRunServiceImpl(agentRunMapper, agentRunStepMapper);
    }

    @Test
    void startRun_shouldInsertRunningRun() {
        service.startRun(1L, "trace-1", "question");

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().getQuery()).isEqualTo("question");
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void recordStepStarted_shouldInsertRunningStep() {
        service.recordStepStarted(1L, "trace-1", 1, "Query", "get_total_expense");

        ArgumentCaptor<AgentRunStep> captor = ArgumentCaptor.forClass(AgentRunStep.class);
        verify(agentRunStepMapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-1");
        assertThat(captor.getValue().getStepNumber()).isEqualTo(1);
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void recordStepFinished_shouldUpdateExistingStep() {
        AgentRunStep step = new AgentRunStep();
        step.setId(9L);
        step.setTraceId("trace-1");
        step.setStepNumber(1);
        when(agentRunStepMapper.selectOne(any())).thenReturn(step);

        service.recordStepFinished(1L, "trace-1", 1, "Query", "get_total_expense", "{}", true, "ok", null);

        assertThat(step.getStatus()).isEqualTo("COMPLETED");
        assertThat(step.getSuccess()).isEqualTo(1);
        assertThat(step.getObservationSummary()).isEqualTo("ok");
        verify(agentRunStepMapper).updateById(step);
    }

    @Test
    void stepsByTraceIds_shouldReturnFrontendStepShape() {
        AgentRunStep step = new AgentRunStep();
        step.setTraceId("trace-1");
        step.setStepNumber(1);
        step.setSummary("Query");
        step.setToolName("get_total_expense");
        step.setSuccess(1);
        step.setStatus("COMPLETED");
        step.setObservationSummary("ok");
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of(step));

        var result = service.stepsByTraceIds(List.of("trace-1"));

        assertThat(result).containsKey("trace-1");
        assertThat(result.get("trace-1").get(0))
                .containsEntry("stepNumber", 1)
                .containsEntry("summary", "Query")
                .containsEntry("tool", "get_total_expense")
                .containsEntry("status", "done")
                .containsEntry("success", true);
    }

    @Test
    void detail_shouldReturnRunWithOrderedSteps() {
        AgentRun run = new AgentRun();
        run.setUserId(1L);
        run.setTraceId("trace-1");
        run.setQuery("question");
        run.setFinalAnswer("answer");
        run.setStatus("COMPLETED");
        run.setStartedAt(LocalDateTime.now().minusSeconds(2));
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(2000L);

        AgentRunStep step = new AgentRunStep();
        step.setTraceId("trace-1");
        step.setStepNumber(1);
        step.setSummary("Query");
        step.setToolName("get_total_expense");
        step.setInput("{}");
        step.setSuccess(1);
        step.setStatus("COMPLETED");
        step.setObservationSummary("ok");
        when(agentRunMapper.selectOne(any())).thenReturn(run);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of(step));

        var detail = service.detail(1L, "trace-1");

        assertThat(detail)
                .containsEntry("traceId", "trace-1")
                .containsEntry("query", "question")
                .containsEntry("finalAnswer", "answer")
                .containsEntry("status", "COMPLETED");
        assertThat((List<?>) detail.get("steps")).hasSize(1);
        Map<?, ?> detailStep = (Map<?, ?>) ((List<?>) detail.get("steps")).get(0);
        assertThat(detailStep.get("tool")).isEqualTo("get_total_expense");
        assertThat(detailStep.get("input")).isEqualTo("{}");
    }
}
