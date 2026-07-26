package com.smartfinance.agent.investment.quant;

import com.smartfinance.agent.investment.domain.HorizonSetting;
import com.smartfinance.agent.investment.domain.ResolvedHorizonProfile;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantAutomationServiceTest {
    @Test
    void dataReadyRunsSavedModelInferenceAndAutomaticSearchForEveryHorizon() {
        InvestmentHorizonService horizonService = mock(InvestmentHorizonService.class);
        QuantInferenceOrchestrator inference = mock(QuantInferenceOrchestrator.class);
        QuantTrainingOrchestrator training = mock(QuantTrainingOrchestrator.class);
        QuantAutomationService service = new QuantAutomationService(
                horizonService,
                inference,
                training
        );
        when(horizonService.resolve(7L, 12L)).thenReturn(new ResolvedHorizonProfile(
                "profile-v1",
                "template-v1",
                List.of(
                        new HorizonSetting(
                                "SHORT", "短期", 5, 3, 10, 5, true, "ASSET"),
                        new HorizonSetting(
                                "MEDIUM", "中期", 20, 10, 60, 20, false, "ASSET")
                ),
                List.of()
        ));
        when(inference.refresh(7L, 12L, "SHORT")).thenReturn(
                Map.of("status", "PAUSED")
        );
        when(inference.refresh(7L, 12L, "MEDIUM")).thenReturn(
                Map.of("status", "QUEUED")
        );
        when(training.refresh(7L, 12L, "SHORT")).thenReturn(
                Map.of("status", "QUEUED")
        );
        when(training.refresh(7L, 12L, "MEDIUM")).thenReturn(
                Map.of("status", "QUEUED")
        );

        Map<String, Object> result = service.onDataReady(7L, 12L);

        assertThat(result)
                .containsEntry("status", "SCHEDULED")
                .containsEntry("assetId", 12L);
        assertThat((List<?>) result.get("updates")).hasSize(2);
        verify(inference).refresh(7L, 12L, "SHORT");
        verify(inference).refresh(7L, 12L, "MEDIUM");
        verify(training).refresh(7L, 12L, "SHORT");
        verify(training).refresh(7L, 12L, "MEDIUM");
    }
}
