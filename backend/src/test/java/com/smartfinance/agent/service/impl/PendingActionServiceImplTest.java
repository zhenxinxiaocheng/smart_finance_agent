package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.entity.AgentMemory;
import com.smartfinance.agent.entity.AgentSchedule;
import com.smartfinance.agent.entity.AgentSkill;
import com.smartfinance.agent.mapper.PendingActionMapper;
import com.smartfinance.agent.dto.AgentMemoryRequest;
import com.smartfinance.agent.dto.CustomSkillDraftRequest;
import com.smartfinance.agent.service.AgentMemoryService;
import com.smartfinance.agent.service.AgentSkillService;
import com.smartfinance.agent.service.AgentScheduleService;
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
import java.util.List;
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
    @Mock
    private AgentScheduleService agentScheduleService;
    @Mock
    private AgentMemoryService agentMemoryService;

    private PendingActionServiceImpl pendingActionService;

    @BeforeEach
    void setUp() {
        pendingActionService = new PendingActionServiceImpl(
                pendingActionMapper,
                transactionService,
                budgetService,
                agentSkillService,
                agentScheduleService,
                agentMemoryService,
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
        assertThat(confirmed.getPayload()).contains("\"resultEntityType\":\"TRANSACTION\"");
        assertThat(confirmed.getPayload()).contains("\"resultEntityId\":99");
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
        Budget budget = new Budget();
        budget.setId(88L);
        when(budgetService.setBudget(1L, "餐饮", "2026-06", new BigDecimal("800.00"), null))
                .thenReturn(budget);

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
        assertThat(confirmed.getPayload()).contains("\"resultEntityType\":\"BUDGET\"");
        assertThat(confirmed.getPayload()).contains("\"resultEntityId\":88");
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
        AgentSkill skill = new AgentSkill();
        skill.setId(77L);
        when(agentSkillService.installCustomSkill(org.mockito.Mockito.eq(1L), any(CustomSkillDraftRequest.class)))
                .thenReturn(skill);

        PendingAction prepared = pendingActionService.prepareCustomSkill(1L, request);

        assertThat(prepared.getActionType()).isEqualTo("INSTALL_CUSTOM_SKILL");
        verify(agentSkillService, never()).installCustomSkill(any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 30L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getPayload()).contains("\"resultEntityType\":\"AGENT_SKILL\"");
        assertThat(confirmed.getPayload()).contains("\"resultEntityId\":77");
        verify(agentSkillService).installCustomSkill(org.mockito.Mockito.eq(1L), any(CustomSkillDraftRequest.class));
    }

    @Test
    void prepareSchedule_shouldCreatePendingAction_thenConfirmCreatesSchedule() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(40L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(40L)).thenAnswer(invocation -> savedAction.get());
        AgentSchedule schedule = new AgentSchedule();
        schedule.setId(66L);
        when(agentScheduleService.create(
                1L,
                "Weekly budget review",
                "Review budget risks every Monday",
                "0 0 9 ? * MON",
                "Review my spending and budget risks",
                "Asia/Shanghai"
        )).thenReturn(schedule);

        PendingAction prepared = pendingActionService.prepareSchedule(
                1L,
                "Weekly budget review",
                "Review budget risks every Monday",
                "0 0 9 ? * MON",
                "Review my spending and budget risks",
                "Asia/Shanghai"
        );

        assertThat(prepared.getActionType()).isEqualTo("CREATE_AGENT_SCHEDULE");
        verify(agentScheduleService, never()).create(any(), any(), any(), any(), any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 40L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getPayload()).contains("\"resultEntityType\":\"AGENT_SCHEDULE\"");
        assertThat(confirmed.getPayload()).contains("\"resultEntityId\":66");
        verify(agentScheduleService).create(
                1L,
                "Weekly budget review",
                "Review budget risks every Monday",
                "0 0 9 ? * MON",
                "Review my spending and budget risks",
                "Asia/Shanghai"
        );
    }

    @Test
    void prepareSchedule_shouldNormalizeFiveFieldCronBeforeConfirming() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(41L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(41L)).thenAnswer(invocation -> savedAction.get());
        AgentSchedule schedule = new AgentSchedule();
        schedule.setId(67L);
        when(agentScheduleService.create(
                1L,
                "每日财务分析提醒",
                "每天分析支出",
                "0 0 10 * * *",
                "汇总当日支出",
                "Asia/Shanghai"
        )).thenReturn(schedule);

        PendingAction prepared = pendingActionService.prepareSchedule(
                1L,
                "每日财务分析提醒",
                "每天分析支出",
                "0 10 * * *",
                "汇总当日支出",
                "Asia/Shanghai"
        );

        assertThat(prepared.getSummary()).contains("0 0 10 * * *");
        assertThat(prepared.getPayload()).contains("\"cronExpression\":\"0 0 10 * * *\"");

        PendingAction confirmed = pendingActionService.confirm(1L, 41L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        verify(agentScheduleService).create(
                1L,
                "每日财务分析提醒",
                "每天分析支出",
                "0 0 10 * * *",
                "汇总当日支出",
                "Asia/Shanghai"
        );
    }

    @Test
    void prepareMemory_shouldCreatePendingAction_thenConfirmCreatesManualMemory() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(50L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));
        when(pendingActionMapper.selectById(50L)).thenAnswer(invocation -> savedAction.get());
        AgentMemoryRequest request = new AgentMemoryRequest();
        request.setMemoryType("RESPONSE_STYLE");
        request.setMemoryKey("concise_answers");
        request.setMemoryValue("以后回答尽量简短。");
        AgentMemory memory = new AgentMemory();
        memory.setId(55L);
        when(agentMemoryService.createManual(org.mockito.Mockito.eq(1L), any(AgentMemoryRequest.class)))
                .thenReturn(memory);

        PendingAction prepared = pendingActionService.prepareMemory(1L, request);

        assertThat(prepared.getActionType()).isEqualTo("INSTALL_AGENT_MEMORY");
        assertThat(prepared.getSummary()).contains("RESPONSE_STYLE", "concise_answers");
        verify(agentMemoryService, never()).createManual(any(), any());

        PendingAction confirmed = pendingActionService.confirm(1L, 50L);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getPayload()).contains("\"resultEntityType\":\"AGENT_MEMORY\"");
        assertThat(confirmed.getPayload()).contains("\"resultEntityId\":55");
        verify(agentMemoryService).createManual(org.mockito.Mockito.eq(1L), any(AgentMemoryRequest.class));
    }

    @Test
    void list_shouldReturnRecentActionsForAuditTrail() {
        PendingAction confirmed = new PendingAction();
        confirmed.setId(60L);
        confirmed.setStatus("CONFIRMED");
        PendingAction cancelled = new PendingAction();
        cancelled.setId(61L);
        cancelled.setStatus("CANCELLED");
        when(pendingActionMapper.selectList(any())).thenReturn(List.of(confirmed, cancelled));

        List<PendingAction> actions = pendingActionService.list(1L, null);

        assertThat(actions).containsExactly(confirmed, cancelled);
        verify(pendingActionMapper).selectList(any());
    }

    @Test
    void prepareSchedule_withSource_shouldPersistReflectionSourceInPayload() {
        AtomicReference<PendingAction> savedAction = new AtomicReference<>();
        doAnswer(invocation -> {
            PendingAction action = invocation.getArgument(0);
            action.setId(70L);
            savedAction.set(action);
            return 1;
        }).when(pendingActionMapper).insert(any(PendingAction.class));

        PendingAction prepared = pendingActionService.prepareSchedule(
                1L,
                "每周预算风险巡检",
                "每周检查预算风险",
                "0 30 17 ? * FRI",
                "检查本周预算风险",
                "Asia/Hong_Kong",
                14L,
                "trace-schedule-edit"
        );

        assertThat(prepared.getPayload()).contains("\"sourceReflectionId\":14");
        assertThat(prepared.getPayload()).contains("\"sourceTraceId\":\"trace-schedule-edit\"");
        assertThat(savedAction.get()).isSameAs(prepared);
    }
}
