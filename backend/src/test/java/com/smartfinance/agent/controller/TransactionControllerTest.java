package com.smartfinance.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartfinance.agent.common.GlobalExceptionHandler;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.config.WebMvcConfig;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = TransactionController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfig.class)
)
@Import(GlobalExceptionHandler.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void add_shouldReturnCreatedTransaction() throws Exception {
        Transaction transaction = buildTransaction();
        when(transactionService.add(eq(1L), any(), any(), any(), any(), any())).thenReturn(transaction);

        String body = """
                {
                  "amount": 88.50,
                  "type": "expense",
                  "category": "food",
                  "description": "lunch",
                  "transactionDate": "2026-05-27"
                }
                """;

        mockMvc.perform(post("/api/transactions")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.category").value("food"));
    }

    @Test
    void list_shouldReturnPagedData() throws Exception {
        Page<Transaction> page = new Page<>(1, 20);
        page.setRecords(List.of(buildTransaction()));
        when(transactionService.listByUser(eq(1L), eq(1), eq(20), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(page);

        mockMvc.perform(get("/api/transactions").requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].id").value(10));
    }

    @Test
    void categorySummary_shouldReturnSummaryList() throws Exception {
        when(transactionService.getCategorySummary(
                1L,
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-05-31")
        )).thenReturn(List.of(Map.of("category", "food", "total", new BigDecimal("500.00"))));

        mockMvc.perform(get("/api/transactions/category-summary")
                        .requestAttr("userId", 1L)
                        .param("startDate", "2026-05-01")
                        .param("endDate", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].category").value("food"));
    }

    @Test
    void delete_shouldReturnSuccess() throws Exception {
        doNothing().when(transactionService).delete(10L, 1L);

        mockMvc.perform(delete("/api/transactions/10").requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private Transaction buildTransaction() {
        Transaction transaction = new Transaction();
        transaction.setId(10L);
        transaction.setUserId(1L);
        transaction.setAmount(new BigDecimal("88.50"));
        transaction.setType("expense");
        transaction.setCategory("food");
        transaction.setDescription("lunch");
        transaction.setTransactionDate(LocalDate.parse("2026-05-27"));
        return transaction;
    }
}
