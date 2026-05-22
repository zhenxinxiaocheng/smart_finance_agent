package com.smartfinance.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class WebSearchTool {

    private final String searchApiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    public WebSearchTool(@Value("${search.api-key:}") String searchApiKey) {
        this.searchApiKey = searchApiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Tool("搜索网络上的财经资讯、汇率、股市等信息，当用户问到实时财经动态时使用此工具")
    public String searchWeb(@P("搜索关键词") String query) {
        if (searchApiKey == null || searchApiKey.isEmpty()) {
            return "搜索功能暂未配置 API Key，无法联网搜索。如需使用请在 application.yml 中配置 search.api-key";
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", searchApiKey,
                    "query", query,
                    "search_depth", "advanced",
                    "include_answer", true,
                    "max_results", 5
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    TAVILY_API_URL, request, String.class);

            String responseBody = response.getBody();
            log.info("Tool[searchWeb] query={}, statusCode={}, response长度={}",
                    query, response.getStatusCode(), responseBody != null ? responseBody.length() : 0);

            return parseTavilyResponse(responseBody, query);
        } catch (Exception e) {
            log.warn("Tool[searchWeb] 搜索失败: {}", e.getMessage());
            return "联网搜索暂时不可用，请稍后再试。错误：" + e.getMessage();
        }
    }

    private String parseTavilyResponse(String responseBody, String query) {
        if (responseBody == null || responseBody.isBlank()) {
            return "未搜索到相关信息";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            StringBuilder result = new StringBuilder();

            result.append("【联网搜索结果 - ").append(query).append("】\n\n");

            JsonNode answer = root.get("answer");
            if (answer != null && !answer.isNull() && !answer.asText().isBlank()) {
                result.append("摘要：").append(answer.asText()).append("\n\n");
            }

            JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                int count = 0;
                for (JsonNode item : results) {
                    count++;
                    String title = item.has("title") ? item.get("title").asText() : "无标题";
                    String url = item.has("url") ? item.get("url").asText() : "";
                    String content = item.has("content") ? item.get("content").asText() : "";
                    double score = item.has("score") ? item.get("score").asDouble() : 0;

                    result.append(count).append(". ").append(title).append("\n");
                    if (!url.isBlank()) {
                        result.append("   来源：").append(url).append("\n");
                    }
                    result.append("   相关性：").append(String.format("%.1f%%", score * 100)).append("\n");
                    result.append("   内容：").append(content).append("\n\n");
                }
                result.append("共找到 ").append(count).append(" 条结果");
            } else {
                result.append("未找到相关结果");
            }

            return result.toString();
        } catch (JsonProcessingException e) {
            log.warn("Tool[searchWeb] 解析响应失败: {}", e.getMessage());
            return "搜索结果解析失败：" + responseBody;
        }
    }
}
