package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.dto.AiBillAnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BillAiClient {

    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen3.5-omni-plus-2026-03-15";
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final String apiKey;
    private final String endpoint;
    private final String multimodalModel;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BillAiClient(@Value("${langchain4j.dashscope.api-key:}") String apiKey,
                        @Value("${bill.ai.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
                        @Value("${bill.ai.multimodal-model:" + DEFAULT_MODEL + "}") String multimodalModel) {
        this(apiKey, endpoint, multimodalModel, new RestTemplate());
    }

    BillAiClient(String apiKey, String endpoint, String multimodalModel, RestTemplate restTemplate) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.multimodalModel = multimodalModel == null || multimodalModel.isBlank() ? DEFAULT_MODEL : multimodalModel.trim();
        this.restTemplate = restTemplate;
    }

    public AiBillAnalysisResponse analyze(MultipartFile file) {
        try {
            if (apiKey.isBlank()) {
                return failed("DashScope API Key 未配置，无法进行多模态账单识别");
            }
            if (file == null || file.isEmpty()) {
                return failed("账单图片不能为空");
            }

            Map<String, Object> payload = buildPayload(file);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, new HttpEntity<>(payload, headers), String.class);
            return normalize(parseDashScopeResponse(response.getBody()));
        } catch (Exception e) {
            return failed("AI账单识别服务调用失败：" + e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(MultipartFile file) throws Exception {
        String today = LocalDate.now().toString();
        String imageUrl = toDataUrl(file);
        return Map.of(
                "model", multimodalModel,
                "temperature", 0.1,
                "modalities", List.of("text"),
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", List.of(Map.of(
                                        "type", "text",
                                        "text", "你是个人财务系统的账单图像识别器。请直接观察图片，判断账单来源，并抽取可导入的交易候选。只输出严格 JSON，不要输出 Markdown 或推理过程。"
                                ))
                        ),
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of(
                                                "type", "text",
                                                "text", """
                                                        请返回 JSON：
                                                        {"billType":"WECHAT|ALIPAY|BANK|NON_BILL|LOW_QUALITY|UNKNOWN","confidence":0.0,"ocrText":"图片中与交易有关的文本摘要","candidates":[{"amount":35.00,"type":"EXPENSE|INCOME","category":"餐饮/交通/购物/工资/投资理财/教育培训/其他","description":"简短交易描述","transactionDate":"YYYY-MM-DD","confidence":0.0}],"warnings":["低置信度、图片模糊、信息缺失等提示"]}
                                                        金额只抽取确定的交易金额，不要把余额、优惠、积分当作交易金额。
                                                        """
                                        ),
                                        Map.of(
                                                "type", "text",
                                                "text", "当前日期是 " + today + "。如果图片出现“今天”或“今日”，transactionDate 必须填写 " + today + "。系统只保存交易日期，不单独保存时分秒。"
                                        ),
                                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                                )
                        )
                )
        );
    }

    private AiBillAnalysisResponse parseDashScopeResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text;
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : content) {
                builder.append(item.path("text").asText());
            }
            text = builder.toString();
        } else {
            text = content.asText();
        }

        String json = extractJsonObject(text);
        return objectMapper.readValue(json, AiBillAnalysisResponse.class);
    }

    private String extractJsonObject(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Matcher matcher = JSON_OBJECT.matcher(trimmed);
        if (!matcher.find()) {
            throw new IllegalArgumentException("模型未返回 JSON 对象");
        }
        return matcher.group();
    }

    private String toDataUrl(MultipartFile file) throws Exception {
        String contentType = file.getContentType();
        String mimeType = contentType == null || contentType.isBlank() ? MediaType.IMAGE_PNG_VALUE : contentType;
        String encoded = Base64.getEncoder().encodeToString(file.getBytes());
        return "data:" + mimeType + ";base64," + encoded;
    }

    private AiBillAnalysisResponse normalize(AiBillAnalysisResponse response) {
        if (response == null) {
            return failed("模型返回为空");
        }
        if (response.getBillType() == null || response.getBillType().isBlank()) {
            response.setBillType("UNKNOWN");
        }
        if (response.getConfidence() == null) {
            response.setConfidence(BigDecimal.ZERO);
        }
        if (response.getOcrText() == null) {
            response.setOcrText("");
        }
        if (response.getCandidates() == null) {
            response.setCandidates(List.of());
        }
        if (response.getWarnings() == null) {
            response.setWarnings(List.of());
        }
        return response;
    }

    private AiBillAnalysisResponse failed(String warning) {
        AiBillAnalysisResponse fallback = new AiBillAnalysisResponse();
        fallback.setBillType("ANALYSIS_FAILED");
        fallback.setConfidence(BigDecimal.ZERO);
        fallback.setOcrText("");
        fallback.getWarnings().add(warning);
        return fallback;
    }
}
