package com.smartfinance.agent.service.impl;

import com.smartfinance.agent.dto.FinancialProfileRequest;
import com.smartfinance.agent.entity.FinancialProfile;
import com.smartfinance.agent.mapper.FinancialProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialProfileServiceImplTest {

    @Mock
    private FinancialProfileMapper financialProfileMapper;

    private FinancialProfileServiceImpl financialProfileService;

    @BeforeEach
    void setUp() {
        financialProfileService = new FinancialProfileServiceImpl(financialProfileMapper);
    }

    @Test
    void saveProfile_shouldCreateProfileWhenMissing() {
        AtomicReference<FinancialProfile> saved = new AtomicReference<>();
        when(financialProfileMapper.selectByUserId(1L)).thenReturn(null);
        doAnswer(invocation -> {
            FinancialProfile profile = invocation.getArgument(0);
            profile.setId(10L);
            saved.set(profile);
            return 1;
        }).when(financialProfileMapper).insert(any(FinancialProfile.class));

        FinancialProfile profile = financialProfileService.save(1L, request());

        assertThat(profile.getId()).isEqualTo(10L);
        assertThat(saved.get().getUserId()).isEqualTo(1L);
        assertThat(saved.get().getRiskPreference()).isEqualTo("STEADY");
        assertThat(saved.get().getMonthlyBudgetGoal()).isEqualByComparingTo("1800.00");
    }

    @Test
    void saveProfile_shouldUpdateExistingProfileForSameUser() {
        FinancialProfile existing = new FinancialProfile();
        existing.setId(7L);
        existing.setUserId(1L);
        existing.setRiskPreference("CONSERVATIVE");
        when(financialProfileMapper.selectByUserId(1L)).thenReturn(existing);

        FinancialProfile profile = financialProfileService.save(1L, request());

        assertThat(profile.getId()).isEqualTo(7L);
        assertThat(profile.getRiskPreference()).isEqualTo("STEADY");
        verify(financialProfileMapper).updateById(existing);
    }

    @Test
    void buildAgentContext_shouldReturnReadableProfileSummary() {
        FinancialProfile profile = new FinancialProfile();
        profile.setLifeStage("学生");
        profile.setMonthlyIncome(new BigDecimal("3000.00"));
        profile.setFixedExpense(new BigDecimal("900.00"));
        profile.setRiskPreference("CONSERVATIVE");
        profile.setSavingsGoalAmount(new BigDecimal("5000.00"));
        profile.setSavingsGoalDeadline("2026-12");
        profile.setMonthlyBudgetGoal(new BigDecimal("1800.00"));
        when(financialProfileMapper.selectByUserId(1L)).thenReturn(profile);

        String context = financialProfileService.buildAgentContext(1L);

        assertThat(context).contains("学生");
        assertThat(context).contains("月收入：3000.00 元");
        assertThat(context).contains("风险偏好：保守");
        assertThat(context).contains("储蓄目标：5000.00 元，期限：2026-12");
        assertThat(context).contains("月度总预算目标：1800.00 元");
    }

    private FinancialProfileRequest request() {
        FinancialProfileRequest request = new FinancialProfileRequest();
        request.setLifeStage("学生");
        request.setMonthlyIncome(new BigDecimal("3000.00"));
        request.setFixedExpense(new BigDecimal("900.00"));
        request.setRiskPreference("STEADY");
        request.setSavingsGoalAmount(new BigDecimal("5000.00"));
        request.setSavingsGoalDeadline("2026-12");
        request.setMonthlyBudgetGoal(new BigDecimal("1800.00"));
        request.setNotes("优先攒应急金");
        return request;
    }
}
