package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.smartfinance.agent.investment.quant.workbench.WorkbenchAnalysisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.smartfinance.agent.investment.quant.workbench.experiment.ExperimentInvariant.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentCandidateErrorTest {
    @ParameterizedTest
    @CsvSource({
            "PARAMETER_NOT_APPLICABLE,当前策略不支持对该参数进行敏感性检查",
            "SYMMETRIC_RANGE_UNAVAILABLE,当前参数附近无法形成完整的五点对称测试区间",
            "INVALID_BASELINE,当前参数值无法用于敏感性检查",
            "FUTURE_CANDIDATE_REASON,当前参数无法完成敏感性检查"
    })
    void structuredCandidate422KeepsStrictMachineCodeAndUsesSafeProductMessage(
            String reasonCode, String safeMessage) {
        var client = mock(WorkbenchAnalysisClient.class);
        when(client.candidates(anyMap())).thenThrow(candidate422(reasonCode, "python /private/path detail"));
        var candidates = new ExperimentCandidateService(client);

        assertThatThrownBy(() -> candidates.generate(
                Map.of("slowWindow", 60), "slowWindow", 1,
                Map.of("candidateRuleVersion", "rule")))
                .isInstanceOfSatisfying(ExperimentInvariant.ExperimentException.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_EXPERIMENT_REQUEST");
                    assertThat(error.reasonCode()).isEqualTo(reasonCode);
                    assertThat(error.safeMessage()).isEqualTo(safeMessage).doesNotContain("/private");
                });
    }

    @ParameterizedTest
    @CsvSource({"lower_case", "BAD-CODE", "' A_LEADING_SPACE'", "'TRAILING_SPACE '", "''"})
    void malformedCandidateCodeRemainsAnUpstreamFailure(String reasonCode) {
        var client = mock(WorkbenchAnalysisClient.class);
        when(client.candidates(anyMap())).thenThrow(candidate422(reasonCode, "python internal message"));

        assertThatThrownBy(() -> new ExperimentCandidateService(client).generate(
                Map.of("slowWindow", 60), "slowWindow", 1,
                Map.of("candidateRuleVersion", "rule")))
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(ExperimentInvariant.ExperimentException.class);
    }

    @Test
    void malformedCandidateBodyRemainsAnUpstreamFailure() {
        var client = mock(WorkbenchAnalysisClient.class);
        when(client.candidates(anyMap())).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", HttpHeaders.EMPTY,
                "not-json".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new ExperimentCandidateService(client).generate(
                Map.of("slowWindow", 60), "slowWindow", 1,
                Map.of("candidateRuleVersion", "rule")))
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(ExperimentInvariant.ExperimentException.class);
    }

    @Test
    void candidateCodeLongerThanContractLimitRemainsAnUpstreamFailure() {
        var client = mock(WorkbenchAnalysisClient.class);
        when(client.candidates(anyMap())).thenThrow(candidate422("A".repeat(81), "python internal message"));

        assertThatThrownBy(() -> new ExperimentCandidateService(client).generate(
                Map.of("slowWindow", 60), "slowWindow", 1,
                Map.of("candidateRuleVersion", "rule")))
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(ExperimentInvariant.ExperimentException.class);
    }

    private static HttpClientErrorException candidate422(String code, String message) {
        var body = encode(Map.of("detail", Map.of("code", code, "message", message)))
                .getBytes(StandardCharsets.UTF_8);
        return HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable",
                HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
    }
}
