package com.smartfinance.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.entity.SkillInvocationRecord;
import com.smartfinance.agent.mapper.PendingActionMapper;
import com.smartfinance.agent.mapper.SkillInvocationRecordMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;

class SkillInvocationOutcomeTest {
    @Test void reflectsConfirmationAndCancellationInsteadOfAssumingSuccess() {
        var mapper = mock(SkillInvocationRecordMapper.class);
        var pendingMapper = mock(PendingActionMapper.class);
        var service = new SkillInvocationRecordServiceImpl(mapper, new ObjectMapper(), pendingMapper);
        var record = new SkillInvocationRecord();
        record.setSuccess(1);
        record.setExecutionState("PENDING");
        record.setPendingActionId(9L);
        var action = new PendingAction();
        action.setId(9L);
        when(mapper.selectList(any())).thenReturn(List.of(record));
        when(pendingMapper.selectList(any())).thenReturn(List.of(action));
        for (String state : List.of("PENDING", "CONFIRMED", "CANCELLED")) {
            action.setStatus(state);
            assertThat(service.list(1L, "record_transaction", 30).get(0).getOutcome())
                    .isEqualTo(state.equals("CONFIRMED") ? "COMPLETED" : state);
        }
        record.setExecutionState(null);
        assertThat(service.list(1L, "record_transaction", 30).get(0).getOutcome()).isEqualTo("UNKNOWN");
        record.setSuccess(0);
        assertThat(service.list(1L, "record_transaction", 30).get(0).getOutcome()).isEqualTo("FAILED");
    }
}
