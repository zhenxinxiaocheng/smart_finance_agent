package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantControllerTest {

    @Test
    void analysisUsesAuthenticatedUser() {
        QuantService service = mock(QuantService.class);
        QuantController controller = new QuantController(service);
        Map<String, Object> analysis = Map.of("action", "NO_TRADE");
        when(service.latestAnalysis(7L, 11L, "WAVE")).thenReturn(analysis);

        assertThat(controller.analysis(7L, 11L, "WAVE").getData()).isSameAs(analysis);

        verify(service).latestAnalysis(7L, 11L, "WAVE");
    }
}
