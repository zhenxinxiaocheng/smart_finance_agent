package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import com.smartfinance.agent.investment.mapper.InvestmentAnalysisSnapshotMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InvestmentAiExplanationServiceTest {

    @Test
    void refreshIfAllowed_shouldRespectThirtyMinuteCooldown() {
        InvestmentAnalysisSnapshotMapper mapper = mock(InvestmentAnalysisSnapshotMapper.class);
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setId(9L);
        snapshot.setSignalHash("same");
        snapshot.setAiExplanation("已有解释");
        snapshot.setAiUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(mapper.selectById(9L)).thenReturn(snapshot);
        InvestmentAiExplanationService service = new InvestmentAiExplanationService(mapper, model, runtimeProperties(30));

        service.refreshIfAllowed(9L, "same", "贵州茅台", "{}", "{}", "{}");

        verifyNoInteractions(model);
        verify(mapper, never()).updateById(any());
    }

    @Test
    void refreshIfAllowed_shouldNotWriteExplanationAfterSignalChangesDuringGeneration() {
        InvestmentAnalysisSnapshotMapper mapper = mock(InvestmentAnalysisSnapshotMapper.class);
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        InvestmentAnalysisSnapshot beforeGeneration = new InvestmentAnalysisSnapshot();
        beforeGeneration.setId(9L);
        beforeGeneration.setSignalHash("expected");
        InvestmentAnalysisSnapshot afterGeneration = new InvestmentAnalysisSnapshot();
        afterGeneration.setId(9L);
        afterGeneration.setSignalHash("newer-signal");
        when(mapper.selectById(9L)).thenReturn(beforeGeneration, afterGeneration);
        when(model.generate(anyList())).thenReturn(Response.from(AiMessage.from("过时解释")));
        InvestmentAiExplanationService service = new InvestmentAiExplanationService(mapper, model, runtimeProperties(30));

        service.refreshIfAllowed(9L, "expected", "贵州茅台", "{}", "{}", "{}");

        verify(mapper, never()).updateById(any());
    }

    @Test
    void refreshIfAllowed_shouldUseConfiguredCooldown() {
        InvestmentAnalysisSnapshotMapper mapper = mock(InvestmentAnalysisSnapshotMapper.class);
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setId(9L);
        snapshot.setSignalHash("same");
        snapshot.setAiUpdatedAt(LocalDateTime.now().minusMinutes(20));
        when(mapper.selectById(9L)).thenReturn(snapshot);
        InvestmentAiExplanationService service = new InvestmentAiExplanationService(mapper, model, runtimeProperties(45));

        service.refreshIfAllowed(9L, "same", "资产", "{}", "{}", "{}");

        verifyNoInteractions(model);
    }

    @Test
    void sanitizesPlaceholdersAndInternalEnumsBeforeCallingAi() {
        InvestmentAiExplanationService service = new InvestmentAiExplanationService(
                mock(InvestmentAnalysisSnapshotMapper.class),
                mock(ChatLanguageModel.class),
                runtimeProperties(30)
        );

        String sanitized = service.sanitizeJson("""
                {
                  "score":33.4,
                  "empty":null,
                  "budget":0E-8,
                  "internal":"NOT_APPLICABLE",
                  "items":[{"name":"趋势"},{"name":"趋势"}]
                }
                """);

        assertThat(sanitized)
                .contains("\"score\":33.4")
                .doesNotContain("empty", "0E-8", "NOT_APPLICABLE");
        assertThat(sanitized.split("趋势", -1)).hasSize(2);
    }

    @Test
    void storesACompactStructuredExplanation() throws Exception {
        InvestmentAnalysisSnapshotMapper mapper = mock(InvestmentAnalysisSnapshotMapper.class);
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        InvestmentAnalysisSnapshot snapshot = new InvestmentAnalysisSnapshot();
        snapshot.setId(9L);
        snapshot.setSignalHash("same");
        when(mapper.selectById(9L)).thenReturn(snapshot, snapshot);
        when(model.generate(anyList())).thenReturn(Response.from(AiMessage.from("""
                {
                  "summary":"短期偏弱，先观察",
                  "reasons":["趋势走弱","波动升高","回撤扩大","多余原因"],
                  "risks":["可能继续下跌","数据仍在更新","流动性较低","多余风险"],
                  "technicalDetails":"模型只负责解释已有结果"
                }
                """)));
        InvestmentAiExplanationService service =
                new InvestmentAiExplanationService(mapper, model, runtimeProperties(30));

        service.refreshIfAllowed(9L, "same", "测试资产", "{}", "{}", "{}");

        verify(mapper).updateById(snapshot);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(snapshot.getAiExplanation(), Map.class);
        assertThat(saved).containsEntry("summary", "短期偏弱，先观察");
        assertThat((java.util.List<?>) saved.get("reasons")).hasSize(3);
        assertThat((java.util.List<?>) saved.get("risks")).hasSize(3);
    }

    private static InvestmentRuntimeProperties runtimeProperties(int cooldownMinutes) {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getAi().setCooldownMinutes(cooldownMinutes);
        properties.getAi().setMinimumParagraphs(3);
        properties.getAi().setMaximumParagraphs(5);
        properties.getAi().setMaxExplanationCharacters(4000);
        properties.getAi().setMaxInputJsonCharacters(6000);
        properties.getAi().setSummaryMaxCharacters(48);
        properties.getAi().setMaximumReasons(3);
        properties.getAi().setMaximumRisks(3);
        properties.getAi().setTechnicalDetailMaxCharacters(300);
        return properties;
    }
}
