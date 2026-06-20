package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.AiBillAnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class BillAiClientTest {

    @Test
    void analyze_shouldCallDashScopeMultimodalModelAndParseCandidates() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        BillAiClient client = new BillAiClient(
                "test-key",
                "https://dashscope.example.com/compatible-mode/v1/chat/completions",
                "qwen-vl-test",
                restTemplate
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "wechat.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );

        server.expect(requestTo("https://dashscope.example.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen-vl-test"))
                .andExpect(jsonPath("$.messages[1].content[2].image_url.url").value("data:image/png;base64,AQID"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\\"billType\\":\\"WECHAT\\",\\"confidence\\":0.88,\\"ocrText\\":\\"微信账单 午餐 35 元\\",\\"candidates\\":[{\\"amount\\":35.00,\\"type\\":\\"EXPENSE\\",\\"category\\":\\"餐饮\\",\\"description\\":\\"午餐\\",\\"transactionDate\\":\\"2026-06-01\\",\\"confidence\\":0.77}],\\"warnings\\":[]}"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        AiBillAnalysisResponse result = client.analyze(file);

        assertThat(result.getBillType()).isEqualTo("WECHAT");
        assertThat(result.getConfidence()).isEqualByComparingTo(new BigDecimal("0.88"));
        assertThat(result.getOcrText()).contains("微信账单");
        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getAmount()).isEqualByComparingTo("35.00");
        assertThat(result.getCandidates().get(0).getCategory()).isEqualTo("餐饮");
        server.verify();
    }

    @Test
    void analyze_whenApiKeyMissing_shouldReturnFailedResponseWithoutHttpCall() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        BillAiClient client = new BillAiClient("", "https://dashscope.example.com", "qwen-vl-test", restTemplate);
        MockMultipartFile file = new MockMultipartFile("file", "bill.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1});

        AiBillAnalysisResponse result = client.analyze(file);

        assertThat(result.getBillType()).isEqualTo("ANALYSIS_FAILED");
        assertThat(result.getCandidates()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("DashScope API Key"));
        server.verify();
    }
}
