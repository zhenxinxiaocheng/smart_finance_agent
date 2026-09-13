package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentProductTransportTest {
    @Test
    void analysisTransportFailureIsNotReportedAsCompatibilityFailure() {
        var commands = mock(ExperimentService.class);
        var repository = mock(ExperimentRepository.class);
        when(commands.create(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("connection refused"));
        var products = new ExperimentProductService(commands, repository);

        assertThatThrownBy(() -> products.create(7L,
                new ExperimentProductDtos.CreateRequest("source", "slowWindow", "参数敏感性"), "key"))
                .isInstanceOf(ExperimentTransportException.class)
                .hasMessage("ANALYSIS_SERVICE_UNAVAILABLE");
    }
}
