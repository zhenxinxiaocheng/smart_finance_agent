package com.smartfinance.agent.investment.quant.workbench.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExperimentControllerTest {
    private ExperimentProductService service;
    private MockMvc mvc;
    private ObjectMapper json;

    @BeforeEach
    void setup() {
        service = mock(ExperimentProductService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExperimentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        json = new ObjectMapper();
    }

    @Test
    void postReturnsProductDtoAndUsesIdempotencyHeader() throws Exception {
        var response = new ExperimentProductDtos.ExperimentListItemResponse(
                "experiment", "参数敏感性", "QUEUED", "source", "strategy", "strategy-v1",
                new ExperimentProductDtos.ParameterResponse("slowWindow", 60, List.of(48, 54, 60, 66, 72)),
                null, null, null, null, 1, "now", "now", null);
        when(service.create(anyLong(), any(), anyString())).thenReturn(response);
        mvc.perform(post("/api/quant/v2/experiments")
                        .requestAttr("userId", 7L)
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ExperimentProductDtos.CreateRequest(
                                "source", "slowWindow", "参数敏感性"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("experiment"))
                .andExpect(jsonPath("$.data.parameter.candidates.length()").value(5));
        verify(service).create(anyLong(), any(), anyString());
    }

    @Test
    void compatibilityFailurePreservesSafeMessageAndReasonCode() throws Exception {
        when(service.create(anyLong(), any(), anyString())).thenThrow(
                new ExperimentInvariant.ExperimentException(
                        "CURRENT_RUNTIME_CONFIG_INCOMPATIBLE", "MODEL_CONFIG_MISMATCH",
                        "来源回测与当前运行环境的研究合同不兼容，请重新执行正式回测。"));
        mvc.perform(post("/api/quant/v2/experiments")
                        .requestAttr("userId", 7L).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceBacktestId\":\"source\",\"parameterKey\":\"slowWindow\",\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("来源回测与当前运行环境的研究合同不兼容，请重新执行正式回测。"))
                .andExpect(jsonPath("$.data.errorCode").value("CURRENT_RUNTIME_CONFIG_INCOMPATIBLE"))
                .andExpect(jsonPath("$.data.reasonCode").value("MODEL_CONFIG_MISMATCH"));
    }

    @Test
    void missingIdempotencyHeaderUsesExperimentErrorContract() throws Exception {
        when(service.create(anyLong(), any(), org.mockito.ArgumentMatchers.isNull())).thenThrow(
                new ExperimentInvariant.ExperimentException(
                        "INVALID_EXPERIMENT_REQUEST", "IDEMPOTENCY_KEY_REQUIRED", "缺少有效的 Idempotency-Key"));
        mvc.perform(post("/api/quant/v2/experiments")
                        .requestAttr("userId", 7L).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceBacktestId\":\"source\",\"parameterKey\":\"slowWindow\",\"name\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_EXPERIMENT_REQUEST"))
                .andExpect(jsonPath("$.data.reasonCode").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void domainErrorsUseTheirRequiredHttpStatusesWithoutRawMessages() throws Exception {
        when(service.detail(7L, "missing")).thenThrow(new ExperimentRepository.ExperimentNotFoundException());
        mvc.perform(get("/api/quant/v2/experiments/missing").requestAttr("userId", 7L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("EXPERIMENT_NOT_FOUND"));

        when(service.detail(7L, "unavailable")).thenThrow(new ExperimentTransportException(
                new IllegalStateException("raw transport failure")));
        mvc.perform(get("/api/quant/v2/experiments/unavailable").requestAttr("userId", 7L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("分析服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.data.errorCode").value("ANALYSIS_SERVICE_UNAVAILABLE"));

        when(service.list(7L, null, null, "bad")).thenThrow(
                new ExperimentInvariant.ExperimentException("EXPERIMENT_REQUEST_CONFLICT"));
        mvc.perform(get("/api/quant/v2/experiments").requestAttr("userId", 7L).param("status", "bad"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("EXPERIMENT_REQUEST_CONFLICT"));
    }

    @Test
    void eligibilityUsesItsFixedRoute() throws Exception {
        when(service.eligibility(7L, "source")).thenReturn(
                new ExperimentProductDtos.EligibilityResponse(true, null, null));
        mvc.perform(get("/api/quant/v2/experiments/eligibility")
                        .requestAttr("userId", 7L).param("sourceBacktestId", "source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true));
        verify(service).eligibility(7L, "source");
    }
}
