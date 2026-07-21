package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentRuntimeProperties;
import com.smartfinance.agent.investment.entity.InvestmentAnalysisSnapshot;
import com.smartfinance.agent.investment.mapper.InvestmentAnalysisSnapshotMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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

    private static InvestmentRuntimeProperties runtimeProperties(int cooldownMinutes) {
        InvestmentRuntimeProperties properties = new InvestmentRuntimeProperties();
        properties.getAi().setCooldownMinutes(cooldownMinutes);
        properties.getAi().setMinimumParagraphs(3);
        properties.getAi().setMaximumParagraphs(5);
        properties.getAi().setMaxExplanationCharacters(4000);
        properties.getAi().setMaxInputJsonCharacters(6000);
        return properties;
    }
}
