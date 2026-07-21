package com.smartfinance.agent.wealth;

import com.smartfinance.agent.wealth.controller.WealthController;
import com.smartfinance.agent.wealth.dto.WealthBaselineRequest;
import com.smartfinance.agent.wealth.dto.WealthOverviewResponse;
import com.smartfinance.agent.wealth.service.WealthService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WealthControllerTest {

    @Test
    void overviewAndBaseline_shouldAlwaysUseAuthenticatedUserId() {
        WealthService service = mock(WealthService.class);
        WealthController controller = new WealthController(service);
        WealthOverviewResponse expected = new WealthOverviewResponse();
        WealthBaselineRequest request = new WealthBaselineRequest();
        request.setCashBalance(new BigDecimal("80000"));
        when(service.overview(7L)).thenReturn(expected);
        when(service.setBaseline(7L, request.getCashBalance())).thenReturn(expected);

        assertThat(controller.overview(7L).getData()).isSameAs(expected);
        assertThat(controller.setBaseline(7L, request).getData()).isSameAs(expected);
        verify(service).setBaseline(7L, new BigDecimal("80000"));
    }
}
