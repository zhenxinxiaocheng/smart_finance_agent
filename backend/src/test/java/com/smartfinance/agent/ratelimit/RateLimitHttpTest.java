package com.smartfinance.agent.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.controller.AuthController;
import com.smartfinance.agent.controller.ChatController;
import com.smartfinance.agent.dto.ChatRequest;
import com.smartfinance.agent.interceptor.JwtInterceptor;
import com.smartfinance.agent.investment.controller.InvestmentAssetController;
import com.smartfinance.agent.investment.service.InvestmentAssetService;
import com.smartfinance.agent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.MappedInterceptor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RateLimitHttpTest {
    private ApiRateLimiter limiter;
    private UserService users;
    private ChatService chats;
    private InvestmentAssetService assets;
    private ChatController chatController;
    private RateLimitInterceptor interceptor;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        limiter = mock(ApiRateLimiter.class);
        users = mock(UserService.class);
        chats = mock(ChatService.class);
        assets = mock(InvestmentAssetService.class);
        var jwt = mock(JwtUtils.class);
        when(jwt.isTokenValid("test-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("test-token")).thenReturn(7L);
        var mapper = new ObjectMapper().findAndRegisterModules();
        interceptor = new RateLimitInterceptor(limiter, mapper);
        chatController = new ChatController(chats, mock(ChatConversationService.class));
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(users, jwt), chatController,
                new InvestmentAssetController(assets))
                .addInterceptors(new MappedInterceptor(new String[]{"/api/**"}, new String[]{"/api/auth/login"},
                        new JwtInterceptor(jwt, mapper)), interceptor).build();
        when(limiter.acquire(any(), anyString())).thenReturn(new ApiRateLimiter.Decision(false, 12));
    }

    @Test
    void loginUsesRemoteAddressAndReturnsRetryAfterWithoutCallingBusiness() throws Exception {
        mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "1.2.3.4")
                        .contentType("application/json").content("{\"username\":\"user\",\"password\":\"pass\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.code").value(429)).andExpect(jsonPath("$.message").value("操作太频繁，请在 12 秒后重试"));
        verify(limiter).acquire(RateLimitScope.LOGIN, "127.0.0.1");
        verifyNoInteractions(users);
    }

    @Test
    void ordinaryAndStreamingChatShareScopeAndRejectBeforeOpeningStream() throws Exception {
        for (String path : new String[]{"/api/chat", "/api/chat/react/stream"}) {
            mvc.perform(post(path).header("Authorization", "Bearer test-token")
                            .contentType("application/json").content("{\"conversationId\":1,\"message\":\"hello\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(429))
                    .andExpect(request().asyncNotStarted());
        }
        verify(limiter, times(2)).acquire(RateLimitScope.CHAT, "7");
        verifyNoInteractions(chats);
    }

    @Test
    void authenticationRunsBeforeUserLimitAndQueriesDoNotConsumeIt() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat/history").param("conversationId", "1")
                .header("Authorization", "Bearer test-token")).andExpect(status().isOk());
        verifyNoInteractions(limiter);
    }

    @Test
    void onlyManualForceRefreshIsLimitedIncludingSpringBooleanAliases() throws Exception {
        mvc.perform(post("/api/investment/assets/refresh").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
        verifyNoInteractions(limiter);
        for (String force : new String[]{"true", "1", "on", "yes"}) {
            mvc.perform(post("/api/investment/assets/refresh").param("force", force)
                    .header("Authorization", "Bearer test-token")).andExpect(status().isTooManyRequests());
        }
        mvc.perform(post("/api/investment/assets/1/sync").header("Authorization", "Bearer test-token"))
                .andExpect(status().isTooManyRequests());
        verify(limiter, times(5)).acquire(RateLimitScope.QUOTE_REFRESH, "7");
        verify(assets).refreshAll(7L, false);
        verify(assets, never()).sync(anyLong(), anyLong());
    }

    @Test
    void asyncRedispatchDoesNotChargeAnAcceptedRequestTwice() throws Exception {
        when(limiter.acquire(any(), anyString())).thenReturn(new ApiRateLimiter.Decision(true, 0));
        var request = new MockHttpServletRequest("POST", "/api/chat/react/stream");
        request.setAttribute("userId", 7L);
        var response = new MockHttpServletResponse();
        var handler = new HandlerMethod(chatController,
                ChatController.class.getMethod("reactStream", Long.class, ChatRequest.class));
        interceptor.preHandle(request, response, handler);
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        interceptor.preHandle(request, response, handler);
        verify(limiter, times(1)).acquire(RateLimitScope.CHAT, "7");
    }
}
