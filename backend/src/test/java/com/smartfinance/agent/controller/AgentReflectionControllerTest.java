package com.smartfinance.agent.controller;

import com.smartfinance.agent.entity.AgentReflection;
import com.smartfinance.agent.dto.AgentReflectionAcceptRequest;
import com.smartfinance.agent.service.AgentReflectionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReflectionControllerTest {

    @Test
    void list_shouldReturnUserReflectionsWithOptionalStatusAndSuggestionType() {
        AgentReflectionService service = mock(AgentReflectionService.class);
        AgentReflectionController controller = new AgentReflectionController(service);
        AgentReflection reflection = new AgentReflection();
        reflection.setId(7L);
        reflection.setUserId(1L);
        reflection.setStatus("OPEN");
        reflection.setSuggestionType("MEMORY_CANDIDATE");
        when(service.list(1L, "OPEN", "MEMORY_CANDIDATE")).thenReturn(List.of(reflection));

        var result = controller.list(1L, "OPEN", "MEMORY_CANDIDATE");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsExactly(reflection);
        verify(service).list(1L, "OPEN", "MEMORY_CANDIDATE");
    }

    @Test
    void accept_shouldDelegateToReflectionService() {
        AgentReflectionService service = mock(AgentReflectionService.class);
        AgentReflectionController controller = new AgentReflectionController(service);
        AgentReflection reflection = new AgentReflection();
        reflection.setId(8L);
        reflection.setStatus("ACCEPTED");
        AgentReflectionAcceptRequest request = new AgentReflectionAcceptRequest();
        request.setName("每周预算复盘");
        when(service.accept(1L, 8L, request)).thenReturn(reflection);

        var result = controller.accept(1L, 8L, request);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getStatus()).isEqualTo("ACCEPTED");
        verify(service).accept(1L, 8L, request);
    }

    @Test
    void dismiss_shouldDelegateToReflectionService() {
        AgentReflectionService service = mock(AgentReflectionService.class);
        AgentReflectionController controller = new AgentReflectionController(service);
        AgentReflection reflection = new AgentReflection();
        reflection.setId(9L);
        reflection.setStatus("DISMISSED");
        when(service.dismiss(1L, 9L)).thenReturn(reflection);

        var result = controller.dismiss(1L, 9L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getStatus()).isEqualTo("DISMISSED");
        verify(service).dismiss(1L, 9L);
    }
}
