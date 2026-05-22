package com.smartfinance.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartfinance.agent.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface TransactionService {

    Transaction add(Long userId, BigDecimal amount, String type, String category, String description, LocalDate transactionDate);

    Transaction update(Long id, Long userId, BigDecimal amount, String type, String category, String description, LocalDate transactionDate);

    void delete(Long id, Long userId);

    Transaction getById(Long id, Long userId);

    IPage<Transaction> listByUser(Long userId, int page, int size, String type, String category, LocalDate startDate, LocalDate endDate);

    List<Map<String, Object>> getCategorySummary(Long userId, LocalDate startDate, LocalDate endDate);
}
