package com.smartfinance.agent.investment.quant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantResearchControllerTest {

    @Test
    void exposesCatalogAndAuthenticatedExperimentOperations() {
        QuantResearchService service = mock(QuantResearchService.class);
        QuantResearchController controller = new QuantResearchController(service);
        List<Map<String, Object>> families = List.of(Map.of("code", "A_SHARE_STOCK"));
        List<Map<String, Object>> universes = List.of(Map.of(
                "id", 3L,
                "modelFamily", "A_SHARE_STOCK"
        ));
        Map<String, Object> schema = Map.of("validationMode", "STRICT");
        Map<String, Object> created = Map.of("id", 99L, "status", "QUEUED");
        QuantResearchController.ExperimentRequest request =
                new QuantResearchController.ExperimentRequest(
                        12L,
                        "A_SHARE_STOCK",
                        "SHORT",
                        Map.of("learningRate", 0.05)
                );
        when(service.modelFamilies()).thenReturn(families);
        when(service.researchUniverses()).thenReturn(universes);
        when(service.parameterSchema()).thenReturn(schema);
        when(service.createExperiment(7L, request)).thenReturn(created);
        when(service.experiments(7L)).thenReturn(List.of(created));
        when(service.experiment(7L, 99L)).thenReturn(created);

        assertThat(controller.modelFamilies().getData()).isSameAs(families);
        assertThat(controller.researchUniverses().getData()).isSameAs(universes);
        assertThat(controller.parameterSchema().getData()).isSameAs(schema);
        assertThat(controller.createExperiment(7L, request).getData()).isSameAs(created);
        assertThat(controller.experiments(7L).getData()).containsExactly(created);
        assertThat(controller.experiment(7L, 99L).getData()).isSameAs(created);

        verify(service).createExperiment(7L, request);
        verify(service).experiment(7L, 99L);
    }
}
