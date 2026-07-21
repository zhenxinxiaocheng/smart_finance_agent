package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Objects;

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
            int minimumParagraphs = runtimeProperties.getAi().getMinimumParagraphs();
            int maximumParagraphs = runtimeProperties.getAi().getMaximumParagraphs();
            String response = chatModel.generate(List.of(
                    SystemMessage.from(("""
                            你是投资分析解释助手。只解释给定的确定性分析结果，不修改评分、支撑压力、价位或数量。
                            必须按输入中的动态周期名称逐一解释；有分歧时明确说明。不要承诺收益，不要使用“必涨”“稳赚”。
                            用简洁中文输出 %d 至 %d 段，最后注明“仅作分析参考，不构成投资建议”。
                            """).formatted(minimumParagraphs, maximumParagraphs)),
                    UserMessage.from("""
                            资产：%s
                            技术分析：%s
                            基本面分析：%s
                            个性化数量参考：%s
                            """.formatted(assetName, truncate(technicalJson), truncate(fundamentalJson), truncate(personalizedJson)))
            )).content().text();
            String text = extractAnswer(response);
            if (text == null || text.isBlank()) return;
            InvestmentAnalysisSnapshot latest = snapshotMapper.selectById(snapshotId);
            if (latest == null || !Objects.equals(latest.getSignalHash(), expectedSignalHash)) return;
            int maxCharacters = runtimeProperties.getAi().getMaxExplanationCharacters();
            latest.setAiExplanation(text.length() > maxCharacters ? text.substring(0, maxCharacters) : text);
            latest.setAiUpdatedAt(LocalDateTime.now());
            snapshotMapper.updateById(latest);
        } catch (Exception exception) {
            log.warn("Investment AI explanation refresh failed: snapshotId={}, error={}", snapshotId, exception.getMessage());
        }
    }

    private String extractAnswer(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (!trimmed.startsWith("{")) return trimmed;
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            JsonNode answer = node.get("answer");
            return answer == null ? trimmed : answer.asText();
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private String truncate(String value) {
        if (value == null) return "{}";
        int maxCharacters = runtimeProperties.getAi().getMaxInputJsonCharacters();
        return value.length() <= maxCharacters ? value : value.substring(0, maxCharacters);
    }
}
