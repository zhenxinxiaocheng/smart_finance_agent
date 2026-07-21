package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.investment.dto.InvestmentOverviewResponse;
import com.smartfinance.agent.investment.service.InvestmentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentControllerTest {

    @Test
    void overview_shouldReturnCurrentUsersInvestmentData() {
        InvestmentService service = mock(InvestmentService.class);
        InvestmentOverviewResponse expected = new InvestmentOverviewResponse();
        when(service.overview(7L)).thenReturn(expected);

        InvestmentController controller = new InvestmentController(service);

        assertSame(expected, controller.overview(7L).getData());
    }
}
