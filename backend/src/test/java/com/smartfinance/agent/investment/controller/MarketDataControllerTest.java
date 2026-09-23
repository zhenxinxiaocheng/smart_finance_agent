package com.smartfinance.agent.investment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.interceptor.JwtInterceptor;
import com.smartfinance.agent.investment.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketDataControllerTest {
    @Test
    void apiRequiresJwtAndScopesUniverseMembershipToAuthenticatedUser() throws Exception {
        var service = mock(MarketDataService.class);
        when(service.getUniverseMembers(eq(7L), eq("pool"), any(LocalDate.class)))
                .thenReturn(Map.of("capability", "STATIC", "productIds", List.of(3L)));
        var jwt = new JwtUtils("01234567890123456789012345678901", 60000);
        var mvc = MockMvcBuilders.standaloneSetup(new MarketDataController(service))
                .addInterceptors(new JwtInterceptor(jwt, new ObjectMapper())).build();
        mvc.perform(get("/api/quant/v2/market-data/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quant/v2/market-data/universes/pool/members")
                        .param("asOfDate", "2026-09-23")
                        .header("Authorization", "Bearer " + jwt.generateToken(7L, "researcher")))
                .andExpect(status().isOk());
        verify(service).getUniverseMembers(7L, "pool", LocalDate.of(2026, 9, 23));
        verifyNoMoreInteractions(service);
    }
}
