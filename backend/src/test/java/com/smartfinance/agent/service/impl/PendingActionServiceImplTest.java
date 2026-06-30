package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.PendingActionMapper;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.BudgetService;
import com.smartfinance.agent.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingActionServiceImplTest {

    @Mock
    private PendingActionMapper pendingActionMapper;
    @Mock
    private TransactionService transactionService;
    @Mock
    private BudgetService budgetService;
    @Mock
    private AgentSkillService agentSkillService;

    private PendingActionServiceImpl pendingActionService;

    @BeforeEach
    void setUp() {
        pendingActionService = new PendingActionServiceImpl(
                pendingActionMapper,
                transactionService,
                budgetService,
                agentSkillService,
                new ObjectMapper()
        );
    }

    @Test
    void prepareTransaction_shouldCreatePendingActionWithoutWritingTransaction_thenConfirmWritesTransaction() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(10L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(10L)).thenAnswer(invocation -> savedAction.get());
        Transaction transaction = new Transaction();
        transaction.setId(99L);
        when(transactionService.add(1L, new BigDecimal("25.00"), "EXPENSE", "餐饮", "午饭", LocalDate.parse("2026-06-16")))
                .thenReturn(transaction);

        PendingAction prepared = pendingActionService.prepareTransaction(
                1L,
                new BigDecimal("25.00"),
                "EXPENSE",
                "餐饮",
                "午饭",
                LocalDate.parse("2026-06-16")
        );

        assertThat(prepared.getId()).isEqualTo(10L);
        assertThat(prepared.getStatus()).isEqualTo("PENDING");
        verify(transactionService, never()).add(any(), any(), any(), any(), any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 10L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        verify(transactionService).add(1L, new BigDecimal("25.00"), "EXPENSE", "餐饮", "午饭", LocalDate.parse("2026-06-16"));
        verify(pendingActionMapper).updateById(any(PendingAction.class));
    }

    @Test
    void prepareBudget_shouldCreatePendingActionWithoutWritingBudget_thenConfirmWritesBudget() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(20L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(20L)).thenAnswer(invocation -> savedAction.get());

        PendingAction prepared = pendingActionService.prepareBudget(
                1L,
                "餐饮",
                "2026-06",
                new BigDecimal("800.00")
        );

        assertThat(prepared.getActionType()).isEqualTo("SET_BUDGET");
        verify(budgetService, never()).setBudget(any(), any(), any(), any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 20L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        verify(budgetService).setBudget(1L, "餐饮", "2026-06", new BigDecimal("800.00"), null);
    }
    @Test
    void prepareCustomSkill_shouldCreatePendingAction_thenConfirmInstallsSkill() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(30L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(30L)).thenAnswer(invocation -> savedAction.get());
        CustomSkillDraftRequest request = new CustomSkillDraftRequest();
        request.setName("Stock Search First");
        request.setDescription("Search before stock analysis");
        request.setTriggerText("stock analysis");
        request.setInstructionText("Always search web first.");
        request.setBoundTools(java.util.List.of("search_web"));

        PendingAction prepared = pendingActionService.prepareCustomSkill(1L, request);

        assertThat(prepared.getActionType()).isEqualTo("INSTALL_CUSTOM_SKILL");
        verify(agentSkillService, never()).installCustomSkill(any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 30L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        verify(agentSkillService).installCustomSkill(org.mockito.Mockito.eq(1L), any(CustomSkillDraftRequest.class));
    }
}
