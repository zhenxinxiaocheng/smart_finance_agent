package com.smartfinance.agent.agent;

import com.smartfinance.agent.common.UserIdContext;
import com.smartfinance.agent.entity.PendingAction;
import com.smartfinance.agent.service.PendingActionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRecorderTest {

    @Mock
    private SmartCategorizationService categorizationService;
    @Mock
    private PendingActionService pendingActionService;

    private TransactionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new TransactionRecorder(categorizationService, pendingActionService);
        UserIdContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        UserIdContext.clear();
    }

    @Test
    void recordTransaction_shouldNormalizeChineseExpenseType() {
        PendingAction action = new PendingAction();
        action.setId(99L);
        when(categorizationService.getAvailableCategories("EXPENSE", 1L)).thenReturn(List.of("\u9910\u996e"));
        when(pendingActionService.prepareTransaction(eq(1L), eq(new BigDecimal("20.00")), eq("EXPENSE"),
                eq("\u9910\u996e"), eq("\u5348\u996d"), eq(LocalDate.parse("2026-06-27"))))
                .thenReturn(action);

        recorder.recordTransaction("\u5348\u996d20\u5143", "\u652f\u51fa", new BigDecimal("20.00"),
                "\u9910\u996e", "\u5348\u996d", "2026-06-27");

        verify(pendingActionService).prepareTransaction(eq(1L), eq(new BigDecimal("20.00")),
                eq("EXPENSE"), eq("\u9910\u996e"), eq("\u5348\u996d"), eq(LocalDate.parse("2026-06-27")));
    }

    @Test
    void suggestCategory_shouldNormalizeChineseIncomeType() {
        when(categorizationService.categorize(any(), eq("INCOME"), eq(1L)))
                .thenReturn(new SmartCategorizationService.CategorizationResult("\u5de5\u8d44", "INCOME",
                        0.9, List.of(), "matched"));

        recorder.suggestCategory("\u5de5\u8d44\u5230\u8d26", "\u6536\u5165");

        verify(categorizationService).categorize("\u5de5\u8d44\u5230\u8d26", "INCOME", 1L);
    }
}
