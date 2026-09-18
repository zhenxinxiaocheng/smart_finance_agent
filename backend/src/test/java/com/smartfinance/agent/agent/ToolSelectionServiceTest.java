package com.smartfinance.agent.agent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class ToolSelectionServiceTest {
    @Test void wordingMustNotHideEnabledCapabilities() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.manifest(1L)).thenReturn("record_transaction search_web get_investment_positions");
        ToolSelectionService service = new ToolSelectionService(registry);
        for (String query : java.util.List.of("我中午吃了50", "到账五千", "看看我的持仓", "继续")) {
            assertThat(service.manifest(1L, query)).contains("record_transaction", "get_investment_positions");
        }
        verify(registry, times(4)).manifest(1L);
    }
}
