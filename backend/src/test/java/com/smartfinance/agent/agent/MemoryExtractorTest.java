package com.smartfinance.agent.agent;

import com.smartfinance.agent.service.AgentMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryExtractorTest {

    @Mock
    private ChatLanguageModel chatModel;
    @Mock
    private AgentMemoryService agentMemoryService;

    private MemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new MemoryExtractor(chatModel, agentMemoryService);
    }

    @Test
    void extractAndSave_shouldPersistAllowedLowRiskMemories() {
        when(chatModel.generate(anyList())).thenReturn(response("""
                {"memories":[
                  {"type":"CATEGORY_PREFERENCE","key":"coffee","value":"咖啡归为餐饮","confidence":0.93},
                  {"type":"RESPONSE_STYLE","key":"brevity","value":"回答尽量简短","confidence":0.91}
                ]}
                """));

        extractor.extractAndSave(1L, "以后咖啡算餐饮，回答短一点", "好的");

        verify(agentMemoryService).upsertAutoMemory(1L, "CATEGORY_PREFERENCE", "coffee",
                "咖啡归为餐饮", 0.93, "以后咖啡算餐饮，回答短一点");
        verify(agentMemoryService).upsertAutoMemory(1L, "RESPONSE_STYLE", "brevity",
                "回答尽量简短", 0.91, "以后咖啡算餐饮，回答短一点");
    }

    @Test
    void extractAndSave_shouldIgnoreInvalidJsonAndSensitiveTypes() {
        when(chatModel.generate(anyList()))
                .thenReturn(response("not json"))
                .thenReturn(response("""
                        {"memories":[{"type":"INCOME","key":"monthly","value":"月收入 3000","confidence":0.99}]}
                        """));

        extractor.extractAndSave(1L, "我的月收入 3000", "已了解");
        extractor.extractAndSave(1L, "我的月收入 3000", "已了解");

        verify(agentMemoryService, never()).upsertAutoMemory(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Response<AiMessage> response(String text) {
        return Response.from(AiMessage.from(text));
    }
}
