package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.GlobalExceptionHandler;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.config.WebMvcConfig;
import com.smartfinance.agent.service.ChatConversationService;
import com.smartfinance.agent.service.ChatService;
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
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ChatController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfig.class)
)
@Import(GlobalExceptionHandler.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @MockBean
    private ChatConversationService conversationService;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void chat_shouldReturnAiResponse() throws Exception {
        when(chatService.chat(1L, 7L, "hello", null)).thenReturn("Hi, this is your finance assistant.");

        mockMvc.perform(post("/api/chat")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conversationId": 7,
                                  "message": "hello"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.response").value("Hi, this is your finance assistant."));
    }

    @Test
    void chat_shouldPassChosenThinkingModeToService() throws Exception {
        when(chatService.chat(1L, 7L, "hello", true)).thenReturn("已分析");

        mockMvc.perform(post("/api/chat")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":7,"message":"hello","thinkingMode":"deep"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.response").value("已分析"));
        org.mockito.Mockito.verify(chatService).chat(1L, 7L, "hello", true);
    }

    @Test
    void streamChat_shouldPassChosenThinkingModeToService() {
        var request = new com.smartfinance.agent.dto.ChatRequest();
        request.setConversationId(7L);
        request.setMessage("hello");
        request.setThinkingMode("fast");
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(chatService.streamReactChat(1L, 7L, "hello", false)).thenReturn(emitter);

        org.junit.jupiter.api.Assertions.assertSame(emitter, new ChatController(chatService, conversationService)
                .reactStream(1L, request));
        org.mockito.Mockito.verify(chatService).streamReactChat(1L, 7L, "hello", false);
    }

    @Test
    void chat_shouldRejectUnknownThinkingMode() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":7,"message":"hello","thinkingMode":"unknown"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("思考模式无效"));
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void chat_shouldRejectMissingConversationId() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "hello"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("conversationId不能为空"));
    }

    @Test
    void history_shouldReturnMessages() throws Exception {
        when(chatService.getChatHistory(1L, 7L, 2)).thenReturn(List.of(
                Map.of("role", "user", "message", "hello"),
                Map.of("role", "assistant", "message", "hi")
        ));

        mockMvc.perform(get("/api/chat/history")
                        .requestAttr("userId", 1L)
                        .param("conversationId", "7")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"));
    }

    @Test
    void history_shouldRejectMissingConversationId() throws Exception {
        mockMvc.perform(get("/api/chat/history")
                        .requestAttr("userId", 1L)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少必要参数: conversationId"));
    }
}
