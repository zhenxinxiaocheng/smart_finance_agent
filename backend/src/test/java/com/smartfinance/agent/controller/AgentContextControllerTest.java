package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.GlobalExceptionHandler;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.config.WebMvcConfig;
import com.smartfinance.agent.context.ContextUsageSnapshot;
import com.smartfinance.agent.service.AgentContextCompressionService;
import com.smartfinance.agent.service.AgentContextConfigService;
import com.smartfinance.agent.service.AgentContextUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AgentContextController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfig.class)
)
@Import(GlobalExceptionHandler.class)
class AgentContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentContextCompressionService compressionService;
    @MockBean
    private AgentContextConfigService configService;
    @MockBean
    private AgentContextUsageService usageService;
    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void compress_shouldRequireConversationId() throws Exception {
        mockMvc.perform(post("/api/agent-context/compress")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"traceId\":\"trace-1\",\"scope\":\"CONVERSATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("conversationId is required"));
    }

    @Test
    void compress_shouldPassConversationIdToService() throws Exception {
        mockMvc.perform(post("/api/agent-context/compress")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"traceId\":\"trace-1\",\"scope\":\"CONVERSATION\"}"))
                .andExpect(status().isOk());

        verify(compressionService).compressConversation(eq(1L), eq(7L), eq("trace-1"), eq("CONVERSATION"));
    }

    @Test
    void usage_shouldReturnConversationContextSnapshot() throws Exception {
        when(usageService.previewConversation(1L, 7L)).thenReturn(new ContextUsageSnapshot(
                12000, 1500, 320, 10180, 0.03,
                List.of(), List.of(), List.of()));

        mockMvc.perform(get("/api/agent-context/usage")
                        .requestAttr("userId", 1L)
                        .param("conversationId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.usedTokens").value(320))
                .andExpect(jsonPath("$.data.usageRatio").value(0.03));
    }

    @Test
    void usage_shouldRejectMissingConversationId() throws Exception {
        mockMvc.perform(get("/api/agent-context/usage")
                        .requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少必要参数: conversationId"));
    }
}
