package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import com.smartfinance.agent.investment.mapper.InvestmentAnalysisSnapshotMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class InvestmentAiExplanationService {

    private final InvestmentAnalysisSnapshotMapper snapshotMapper;
    private final ChatLanguageModel chatModel;
    private final InvestmentRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InvestmentAiExplanationService(InvestmentAnalysisSnapshotMapper snapshotMapper,
                                          ChatLanguageModel chatModel,
                                          InvestmentRuntimeProperties runtimeProperties) {
        this.snapshotMapper = snapshotMapper;
        this.chatModel = chatModel;
        this.runtimeProperties = runtimeProperties;
    }

    @Async
    public void refreshIfAllowed(Long snapshotId, String expectedSignalHash, String assetName,
                                 String technicalJson, String fundamentalJson, String personalizedJson) {
        InvestmentAnalysisSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !Objects.equals(snapshot.getSignalHash(), expectedSignalHash)) return;
        if (snapshot.getAiUpdatedAt() != null
                && snapshot.getAiUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(
                runtimeProperties.getAi().getCooldownMinutes()))) return;
        try {
            String response = chatModel.generate(List.of(
                    SystemMessage.from("""
                            你是投资分析解释助手。只解释给定的确定性分析结果，不修改评分、支撑压力、价位或数量。
                            面向没有金融和算法知识的普通用户，使用短句，不承诺收益。
                            只返回 JSON，不要 Markdown：
                            {"summary":"一句结论","reasons":["最多3条原因"],"risks":["最多3条风险"],"technicalDetails":"可展开的技术详情"}
                            summary 不超过 48 个汉字；原因和风险每条只说一件事。
                            """),
                    UserMessage.from("""
                            资产：%s
                            技术分析：%s
                            基本面分析：%s
                            个性化数量参考：%s
                            """.formatted(
                                    assetName,
                                    sanitizeJson(technicalJson),
                                    sanitizeJson(fundamentalJson),
                                    sanitizeJson(personalizedJson)
                            ))
            )).content().text();
            Map<String, Object> explanation = structuredExplanation(response);
            if (String.valueOf(explanation.get("summary")).isBlank()) return;
            InvestmentAnalysisSnapshot latest = snapshotMapper.selectById(snapshotId);
            if (latest == null || !Objects.equals(latest.getSignalHash(), expectedSignalHash)) return;
            latest.setAiExplanation(objectMapper.writeValueAsString(explanation));
            latest.setAiUpdatedAt(LocalDateTime.now());
            snapshotMapper.updateById(latest);
        } catch (Exception exception) {
            log.warn("Investment AI explanation refresh failed: snapshotId={}, error={}", snapshotId, exception.getMessage());
        }
    }

    Map<String, Object> structuredExplanation(String value) {
        String trimmed = stripFence(value);
        JsonNode root = null;
        try {
            root = objectMapper.readTree(trimmed);
            JsonNode answer = root == null ? null : root.get("answer");
            if (answer != null) {
                root = answer.isTextual()
                        ? objectMapper.readTree(stripFence(answer.asText()))
                        : answer;
            }
        } catch (Exception ignored) {
            // A legacy plain-text answer becomes a short summary and detail.
        }
        InvestmentRuntimeProperties.Ai ai = runtimeProperties.getAi();
        Map<String, Object> result = new LinkedHashMap<>();
        if (root == null || !root.isObject()) {
            String summary = limit(firstSentence(trimmed), ai.getSummaryMaxCharacters());
            result.put("summary", summary);
            result.put("reasons", List.of());
            result.put("risks", List.of());
            result.put(
                    "technicalDetails",
                    limit(trimmed, ai.getTechnicalDetailMaxCharacters())
            );
            return result;
        }
        String summary = text(root, "summary", "conclusion");
        result.put("summary", limit(summary, ai.getSummaryMaxCharacters()));
        result.put("reasons", textList(
                root.get("reasons"),
                ai.getMaximumReasons(),
                ai.getSummaryMaxCharacters() * 2
        ));
        result.put("risks", textList(
                root.get("risks"),
                ai.getMaximumRisks(),
                ai.getSummaryMaxCharacters() * 2
        ));
        JsonNode details = root.get("technicalDetails");
        String detailText = details == null
                ? ""
                : details.isTextual()
                ? details.asText()
                : details.toString();
        result.put(
                "technicalDetails",
                limit(detailText, ai.getTechnicalDetailMaxCharacters())
        );
        return result;
    }

    String sanitizeJson(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        try {
            JsonNode cleaned = sanitizeNode(
                    objectMapper.readTree(value),
                    new HashSet<>()
            );
            String result = cleaned == null ? "{}" : objectMapper.writeValueAsString(cleaned);
            return limit(
                    result,
                    runtimeProperties.getAi().getMaxInputJsonCharacters()
            );
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private JsonNode sanitizeNode(JsonNode node, Set<String> seen) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber() && Math.abs(node.asDouble()) <= 1e-12) {
            return null;
        }
        if (node.isTextual()
                && node.asText().matches("[A-Z][A-Z0-9_]{2,}")) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                JsonNode child = sanitizeNode(entry.getValue(), seen);
                if (child != null && !emptyContainer(child)) {
                    result.set(entry.getKey(), child);
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode child = sanitizeNode(item, seen);
                if (child == null || emptyContainer(child)) {
                    continue;
                }
                String signature = child.toString();
                if (seen.add(signature)) {
                    result.add(child);
                }
            }
            return result;
        }
        return node;
    }

    private static boolean emptyContainer(JsonNode node) {
        return (node.isObject() || node.isArray()) && node.isEmpty();
    }

    private static List<String> textList(
            JsonNode node,
            int maximumItems,
            int maximumCharacters
    ) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isBlank() && seen.add(text)) {
                result.add(limit(text, maximumCharacters));
            }
            if (result.size() >= maximumItems) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static String firstSentence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int end = -1;
        for (String separator : List.of("。", "！", "？", "\n")) {
            int index = trimmed.indexOf(separator);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index + separator.length();
            }
        }
        return end < 0 ? trimmed : trimmed.substring(0, end);
    }

    private static String stripFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private static String limit(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maximumCharacters
                ? trimmed
                : trimmed.substring(0, maximumCharacters);
    }
}
