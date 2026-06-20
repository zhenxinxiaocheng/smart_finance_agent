package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.GlobalExceptionHandler;
import com.smartfinance.agent.common.JwtUtils;
import com.smartfinance.agent.config.WebMvcConfig;
import com.smartfinance.agent.entity.Budget;
import com.smartfinance.agent.service.BudgetService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = BudgetController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfig.class)
)
@Import(GlobalExceptionHandler.class)
class BudgetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BudgetService budgetService;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void list_shouldReturnBudgetsAndSummaryForMonth() throws Exception {
        when(budgetService.getUserBudgets(1L, "2026-06")).thenReturn(List.of(buildBudget()));
        when(budgetService.getBudgetSummary(1L, "2026-06")).thenReturn(Map.of(
                "hasBudget", true,
                "totalBudget", new BigDecimal("1800.00")
        ));

        mockMvc.perform(get("/api/budgets")
                        .requestAttr("userId", 1L)
                        .param("month", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].category").value("餐饮"))
                .andExpect(jsonPath("$.data.summary.totalBudget").value(1800.00));
    }

    @Test
    void save_shouldSetBudgetForCurrentUser() throws Exception {
        Budget budget = buildBudget();
        when(budgetService.setBudget(eq(1L), eq("餐饮"), eq("2026-06"), eq(new BigDecimal("600.00")), eq(80)))
                .thenReturn(budget);

        mockMvc.perform(put("/api/budgets")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "category": "餐饮",
                                  "month": "2026-06",
                                  "amount": 600.00,
                                  "alertThreshold": 80
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.category").value("餐饮"))
                .andExpect(jsonPath("$.data.budgetAmount").value(600.00));
    }

    @Test
    void delete_shouldRemoveBudgetForCurrentUser() throws Exception {
        doNothing().when(budgetService).deleteBudget(9L, 1L);

        mockMvc.perform(delete("/api/budgets/9").requestAttr("userId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private Budget buildBudget() {
        Budget budget = new Budget();
        budget.setId(9L);
        budget.setUserId(1L);
        budget.setCategory("餐饮");
        budget.setMonth("2026-06");
        budget.setBudgetAmount(new BigDecimal("600.00"));
        budget.setAlertThreshold(80);
        return budget;
    }
}
