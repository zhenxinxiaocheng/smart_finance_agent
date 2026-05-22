package com.smartfinance.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartfinance.agent.entity.Transaction;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.service.TransactionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class TransactionServiceImpl implements TransactionService {

    private final TransactionMapper transactionMapper;

    public TransactionServiceImpl(TransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    @Override
    public Transaction add(Long userId, BigDecimal amount, String type, String category, String description, LocalDate transactionDate) {
        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        transaction.setAmount(amount);
        transaction.setType(type);
        transaction.setCategory(category);
        transaction.setDescription(description);
        transaction.setTransactionDate(transactionDate);
        transactionMapper.insert(transaction);
        return transaction;
    }

    @Override
    public Transaction update(Long id, Long userId, BigDecimal amount, String type, String category, String description, LocalDate transactionDate) {
        Transaction existing = getById(id, userId);
        existing.setAmount(amount);
        existing.setType(type);
        existing.setCategory(category);
        existing.setDescription(description);
        existing.setTransactionDate(transactionDate);
        transactionMapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long id, Long userId) {
        Transaction existing = getById(id, userId);
        transactionMapper.deleteById(existing.getId());
    }

    @Override
    public Transaction getById(Long id, Long userId) {
        Transaction transaction = transactionMapper.selectById(id);
        if (transaction == null) {
            throw new IllegalArgumentException("交易记录不存在");
        }
        if (!transaction.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该记录");
        }
        return transaction;
    }

    @Override
    public IPage<Transaction> listByUser(Long userId, int page, int size, String type, String category, LocalDate startDate, LocalDate endDate) {
        Page<Transaction> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Transaction> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Transaction::getUserId, userId);
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Transaction::getType, type);
        }
        if (category != null && !category.isEmpty()) {
            wrapper.eq(Transaction::getCategory, category);
        }
        if (startDate != null) {
            wrapper.ge(Transaction::getTransactionDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(Transaction::getTransactionDate, endDate);
        }
        wrapper.orderByDesc(Transaction::getTransactionDate);
        wrapper.orderByDesc(Transaction::getCreatedAt);
        return transactionMapper.selectPage(pageParam, wrapper);
    }

    @Override
    public List<Map<String, Object>> getCategorySummary(Long userId, LocalDate startDate, LocalDate endDate) {
        return transactionMapper.sumByCategory(userId, startDate, endDate);
    }
}
