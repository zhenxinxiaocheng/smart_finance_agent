package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.investment.dto.HorizonProfileRequest;
import com.smartfinance.agent.investment.dto.HorizonProfileResponse;
import com.smartfinance.agent.investment.dto.HorizonSettingRequest;
import com.smartfinance.agent.investment.service.InvestmentHorizonService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentHorizonControllerTest {

    @Test
    void globalProfileEndpointsUseAuthenticatedUser() {
        InvestmentHorizonService service = mock(InvestmentHorizonService.class);
        InvestmentHorizonController controller = new InvestmentHorizonController(service);
        HorizonProfileRequest request = new HorizonProfileRequest(List.of(
                new HorizonSettingRequest("CORE", "核心配置", 10, 120, 900, true)
        ));
        HorizonProfileResponse expected = new HorizonProfileResponse(
                "template:v1|global:3|asset:0", "v1", "GLOBAL", false,
                2500, List.of(), List.of());
        when(service.global(7L)).thenReturn(expected);
        when(service.saveGlobal(7L, request)).thenReturn(expected);

        assertThat(controller.get(7L).getData()).isSameAs(expected);
        assertThat(controller.put(7L, request).getData()).isSameAs(expected);
        verify(service).global(7L);
        verify(service).saveGlobal(7L, request);
    }
}
