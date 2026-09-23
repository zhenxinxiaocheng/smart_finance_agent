package com.smartfinance.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfinance.agent.mapper.TransactionMapper;
import com.smartfinance.agent.dto.TransactionStatisticsRow;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class FinanceStatisticsService {
    private final TransactionMapper transactions;
    private final FinanceStatisticsCache cache;

    public FinanceStatisticsService(TransactionMapper transactions, FinanceStatisticsCache cache) {
        this.transactions = transactions;
        this.cache = cache;
    }

    public BigDecimal sumByUserAndTypeAndDateRange(Long userId, String type, LocalDate start, LocalDate end) {
        return cache.get(userId, Arrays.asList("type", type, start, end), FinanceStatisticsService::amount,
                () -> transactions.sumByUserAndTypeAndDateRange(userId, type, start, end));
    }

    public BigDecimal sumByUserAndCategoryAndDateRange(Long userId, String category, LocalDate start, LocalDate end) {
        return cache.get(userId, Arrays.asList("category", category, start, end), FinanceStatisticsService::amount,
                () -> transactions.sumByUserAndCategoryAndDateRange(userId, category, start, end));
    }

    public List<Map<String, Object>> sumByCategory(Long userId, LocalDate start, LocalDate end) {
        return cache.get(userId, Arrays.asList("categories", start, end), node -> rows(node, "category"),
                () -> transactions.sumByCategory(userId, start, end));
    }

    public List<Map<String, Object>> sumDailyExpense(Long userId, LocalDate start, LocalDate end) {
        return cache.get(userId, Arrays.asList("daily", start, end), node -> rows(node, "date"),
                () -> transactions.sumDailyExpense(userId, start, end));
    }

    public List<TransactionStatisticsRow> statisticsByDateRange(Long userId, LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("请选择有效的统计日期范围");
        }
        return cache.get(userId, Arrays.asList("statistics", start, end), FinanceStatisticsService::statisticsRows,
                () -> transactions.statisticsByDateRange(userId, start, end));
    }

    private static List<TransactionStatisticsRow> statisticsRows(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("Invalid cached statistics");
        List<TransactionStatisticsRow> result = new ArrayList<>();
        for (JsonNode item : node) {
            TransactionStatisticsRow row = new TransactionStatisticsRow();
            row.setTransactionDate(LocalDate.parse(item.path("transactionDate").asText()));
            row.setType(item.path("type").asText());
            row.setCategory(item.path("category").isNull() ? null : item.path("category").asText());
            row.setAmount(amount(item.path("amount")));
            if (!item.path("transactionCount").isIntegralNumber()) throw new IllegalArgumentException("Invalid cached count");
            row.setTransactionCount(item.path("transactionCount").longValue());
            result.add(row);
        }
        return result;
    }

    private static BigDecimal amount(JsonNode node) {
        if (!node.isNumber()) throw new IllegalArgumentException("Invalid cached amount");
        return new BigDecimal(node.asText());
    }

    private static List<Map<String, Object>> rows(JsonNode node, String dimension) {
        if (!node.isArray()) throw new IllegalArgumentException("Invalid cached statistics");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : node) {
            Map<String, Object> row = new LinkedHashMap<>();
            JsonNode label = item.get(dimension);
            if (label == null) throw new IllegalArgumentException("Missing cached dimension");
            row.put(dimension, label.isNull() ? null : label.asText());
            row.put("total", amount(item.path("total")));
            rows.add(row);
        }
        return rows;
    }
}
