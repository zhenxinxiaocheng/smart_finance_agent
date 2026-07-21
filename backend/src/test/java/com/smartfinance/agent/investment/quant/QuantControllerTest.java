package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantControllerTest {

    @Test
    void analysisRefreshJobStrategyAndPaperQueriesUseAuthenticatedUser() {
        QuantService service = mock(QuantService.class);
        QuantController controller = new QuantController(service);
        Map<String, Object> analysis = Map.of("action", "NO_TRADE");
        Map<String, Object> job = Map.of("jobId", "a".repeat(32), "status", "QUEUED");
        when(service.latestAnalysis(7L, 11L, "WAVE")).thenReturn(analysis);
        when(service.refresh(7L, 11L, "WAVE")).thenReturn(job);
        when(service.job(7L, "a".repeat(32))).thenReturn(job);

        assertThat(controller.analysis(7L, 11L, "WAVE").getData()).isSameAs(analysis);
        assertThat(controller.refresh(7L, 11L, new QuantController.RefreshRequest("WAVE")).getData()).isSameAs(job);
        assertThat(controller.job(7L, "a".repeat(32)).getData()).isSameAs(job);

        verify(service).latestAnalysis(7L, 11L, "WAVE");
        verify(service).refresh(7L, 11L, "WAVE");
        verify(service).job(7L, "a".repeat(32));
    }
}
