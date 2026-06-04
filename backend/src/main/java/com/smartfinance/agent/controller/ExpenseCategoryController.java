package com.smartfinance.agent.controller;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.ExpenseCategoryRequest;
import com.smartfinance.agent.entity.ExpenseCategory;
import com.smartfinance.agent.service.ExpenseCategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class ExpenseCategoryController {

    private final ExpenseCategoryService expenseCategoryService;

    public ExpenseCategoryController(ExpenseCategoryService expenseCategoryService) {
        this.expenseCategoryService = expenseCategoryService;
    }

    @GetMapping
    public Result<List<ExpenseCategory>> list(@RequestAttribute Long userId) {
        return Result.success(expenseCategoryService.listByUser(userId));
    }

    @GetMapping("/{id}")
    public Result<ExpenseCategory> getById(@RequestAttribute Long userId,
                                           @PathVariable Long id) {
        return Result.success(expenseCategoryService.getById(id, userId));
    }

    @PostMapping
    public Result<ExpenseCategory> add(@RequestAttribute Long userId,
                                       @Valid @RequestBody ExpenseCategoryRequest request) {
        ExpenseCategory category = expenseCategoryService.add(
                userId, request.getName(), request.getIcon(),
                request.getBenchmarkMin(), request.getBenchmarkMax(),
                request.getBenchmarkLabel(), request.getSortOrder()
        );
        return Result.success(category);
    }

    @PutMapping("/{id}")
    public Result<ExpenseCategory> update(@RequestAttribute Long userId,
                                          @PathVariable Long id,
                                          @Valid @RequestBody ExpenseCategoryRequest request) {
        ExpenseCategory category = expenseCategoryService.update(
                id, userId, request.getName(), request.getIcon(),
                request.getBenchmarkMin(), request.getBenchmarkMax(),
                request.getBenchmarkLabel(), request.getSortOrder()
        );
        return Result.success(category);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId,
                               @PathVariable Long id) {
        expenseCategoryService.delete(id, userId);
        return Result.success();
    }
}
