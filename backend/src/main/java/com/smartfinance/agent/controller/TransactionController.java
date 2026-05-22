package com.smartfinance.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.dto.TransactionRequest;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public Result<Transaction> add(@RequestAttribute Long userId,
                                   @Valid @RequestBody TransactionRequest request) {
        Transaction transaction = transactionService.add(
                userId, request.getAmount(), request.getType(),
                request.getCategory(), request.getDescription(),
                request.getTransactionDate()
        );
        return Result.success(transaction);
    }

    @PutMapping("/{id}")
    public Result<Transaction> update(@RequestAttribute Long userId,
                                      @PathVariable Long id,
                                      @Valid @RequestBody TransactionRequest request) {
        Transaction transaction = transactionService.update(
                id, userId, request.getAmount(), request.getType(),
                request.getCategory(), request.getDescription(),
                request.getTransactionDate()
        );
        return Result.success(transaction);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute Long userId,
                               @PathVariable Long id) {
        transactionService.delete(id, userId);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Transaction> getById(@RequestAttribute Long userId,
                                       @PathVariable Long id) {
        return Result.success(transactionService.getById(id, userId));
    }

    @GetMapping
    public Result<IPage<Transaction>> list(
            @RequestAttribute Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(transactionService.listByUser(
                userId, page, size, type, category, startDate, endDate));
    }

    @GetMapping("/category-summary")
    public Result<List<Map<String, Object>>> categorySummary(
            @RequestAttribute Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(transactionService.getCategorySummary(userId, startDate, endDate));
    }
}
