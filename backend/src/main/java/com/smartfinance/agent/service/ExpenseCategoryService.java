package com.smartfinance.agent.service;

import com.smartfinance.agent.entity.ExpenseCategory;

import java.util.List;

public interface ExpenseCategoryService {

    List<ExpenseCategory> listByUser(Long userId);

    ExpenseCategory getById(Long id, Long userId);

    ExpenseCategory add(Long userId, String name, String icon,
                        Integer benchmarkMin, Integer benchmarkMax,
                        String benchmarkLabel, Integer sortOrder);

    ExpenseCategory update(Long id, Long userId, String name, String icon,
                           Integer benchmarkMin, Integer benchmarkMax,
                           String benchmarkLabel, Integer sortOrder);

    void delete(Long id, Long userId);
}
