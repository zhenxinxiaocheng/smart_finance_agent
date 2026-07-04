package com.smartfinance.agent.controller;

import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingActionControllerTest {

    @Test
    void list_shouldReturnRecentActionsWithOptionalStatusFilter() {
        PendingActionService service = mock(PendingActionService.class);
        PendingAction action = new PendingAction();
        action.setId(7L);
        action.setStatus("CONFIRMED");
        when(service.list(1L, "CONFIRMED")).thenReturn(List.of(action));
        PendingActionController controller = new PendingActionController(service);

        var result = controller.list(1L, "CONFIRMED");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getStatus()).isEqualTo("CONFIRMED");
        verify(service).list(1L, "CONFIRMED");
    }

    @Test
    void confirm_shouldDelegateToService() {
        PendingActionService service = mock(PendingActionService.class);
        PendingAction action = new PendingAction();
        action.setId(8L);
        action.setStatus("CONFIRMED");
        when(service.confirm(1L, 8L)).thenReturn(action);
        PendingActionController controller = new PendingActionController(service);

        var result = controller.confirm(1L, 8L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getStatus()).isEqualTo("CONFIRMED");
    }
}
