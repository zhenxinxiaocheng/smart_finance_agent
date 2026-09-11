package com.smartfinance.agent.investment.controller;

import com.smartfinance.agent.investment.dto.InvestmentIndexCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexReorderRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexView;
import com.smartfinance.agent.investment.service.InvestmentIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentIndexControllerTest {

    @Test
    void endpointsUseTheAuthenticatedUsersWatchlist() {
        InvestmentIndexService service = mock(InvestmentIndexService.class);
        InvestmentIndexController controller = new InvestmentIndexController(service);
        InvestmentIndexCreateRequest request = new InvestmentIndexCreateRequest("GLOBAL_INDEX:SPX");
        InvestmentIndexView expected = new InvestmentIndexView(
                8L, "GLOBAL_INDEX:SPX", "标普500", "US", false,
                null, null, null, null, null, null, null, null, null, "UNAVAILABLE", null
        );
        when(service.list(7L)).thenReturn(List.of(expected));
        when(service.add(7L, request)).thenReturn(expected);

        assertThat(controller.list(7L).getData()).containsExactly(expected);
        assertThat(controller.add(7L, request).getData()).isSameAs(expected);
        controller.remove(7L, 8L);
        InvestmentIndexReorderRequest reorderRequest = new InvestmentIndexReorderRequest(
                List.of("GLOBAL_INDEX:SPX")
        );
        controller.reorder(7L, reorderRequest);

        verify(service).list(7L);
        verify(service).add(7L, request);
        verify(service).remove(7L, 8L);
        verify(service).reorder(7L, reorderRequest);
    }
}
