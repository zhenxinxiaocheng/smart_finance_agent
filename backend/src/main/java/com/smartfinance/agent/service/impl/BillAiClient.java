package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.AiBillAnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Component
public class BillAiClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String analyzeUrl;

    public BillAiClient(@Value("${bill.ai.analyze-url:http://localhost:8090/api/ai/bill/analyze}") String analyzeUrl) {
        this.analyzeUrl = analyzeUrl;
    }

    public AiBillAnalysisResponse analyze(MultipartFile file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() == null ? "bill-image" : file.getOriginalFilename();
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            return restTemplate.postForObject(analyzeUrl, request, AiBillAnalysisResponse.class);
        } catch (Exception e) {
            AiBillAnalysisResponse fallback = new AiBillAnalysisResponse();
            fallback.setBillType("ANALYSIS_FAILED");
            fallback.setOcrText("");
            fallback.getWarnings().add("AI账单识别服务调用失败：" + e.getMessage());
            return fallback;
        }
    }
}
