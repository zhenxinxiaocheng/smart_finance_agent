package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 422})
    void analysisClientErrorsAreServiceFailuresRatherThanParameterErrors(int status) {
        assertTransport(HttpClientErrorException.create(
                HttpStatus.valueOf(status), "analysis failure", null, null, null));
    }

    @Test
    void analysisServerFailureIsTransportFailure() {
        assertTransport(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void analysisTimeoutIsTransportFailure() {
        assertTransport(new ResourceAccessException("read timed out"));
    }

    private void assertTransport(RuntimeException upstream) {
        var commands = mock(ExperimentService.class);
        var repository = mock(ExperimentRepository.class);
        when(commands.create(anyLong(), anyString(), anyString(), anyString(), anyString())).thenThrow(upstream);

        assertThatThrownBy(() -> new ExperimentProductService(commands, repository).create(7L,
                new ExperimentProductDtos.CreateRequest("source", "slowWindow", "参数敏感性"), "key"))
                .isInstanceOf(ExperimentTransportException.class)
                .hasMessage("ANALYSIS_SERVICE_UNAVAILABLE");
    }
}
