package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.TransactionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock
    private TransactionMapper transactionMapper;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    @Test
    void add_shouldPersistAndReturnTransaction() {
        Transaction transaction = transactionService.add(
                1L,
                new BigDecimal("88.50"),
                "expense",
                "food",
                "lunch",
                LocalDate.parse("2026-05-27")
        );

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionMapper).insert(captor.capture());
        Transaction inserted = captor.getValue();

        assertEquals(1L, inserted.getUserId());
        assertEquals(new BigDecimal("88.50"), inserted.getAmount());
        assertEquals(transaction.getCategory(), inserted.getCategory());
    }

    @Test
    void getById_whenOwnerMismatch_shouldThrow() {
        Transaction transaction = new Transaction();
        transaction.setId(10L);
        transaction.setUserId(2L);
        when(transactionMapper.selectById(10L)).thenReturn(transaction);

        assertThrows(IllegalArgumentException.class, () -> transactionService.getById(10L, 1L));
    }

    @Test
    void update_shouldModifyExistingAndCallUpdateById() {
        Transaction transaction = new Transaction();
        transaction.setId(10L);
        transaction.setUserId(1L);
        when(transactionMapper.selectById(10L)).thenReturn(transaction);

        transactionService.update(
                10L,
                1L,
                new BigDecimal("66.00"),
                "income",
                "salary",
                "may salary",
                LocalDate.parse("2026-05-20")
        );

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionMapper).updateById(captor.capture());
        Transaction updated = captor.getValue();
        assertEquals("income", updated.getType());
        assertEquals("salary", updated.getCategory());
    }

    @Test
    void delete_shouldCallDeleteById() {
        Transaction transaction = new Transaction();
        transaction.setId(10L);
        transaction.setUserId(1L);
        when(transactionMapper.selectById(10L)).thenReturn(transaction);

        transactionService.delete(10L, 1L);

        verify(transactionMapper).deleteById(10L);
    }

    @Test
    void categorySummary_shouldDelegateToMapper() {
        when(transactionMapper.sumByCategory(
                1L, LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")
        )).thenReturn(List.of(Map.of("category", "food", "total", new BigDecimal("200.00"))));

        List<Map<String, Object>> result = transactionService.getCategorySummary(
                1L, LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")
        );

        assertEquals(1, result.size());
        assertEquals("food", result.get(0).get("category"));
    }
}
