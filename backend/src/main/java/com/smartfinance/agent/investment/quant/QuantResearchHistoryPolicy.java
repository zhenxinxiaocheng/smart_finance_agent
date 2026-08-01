package com.smartfinance.agent.investment.quant;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

final class QuantResearchHistoryPolicy {
    private QuantResearchHistoryPolicy() {
    }

    static LocalDate resolveStartDate(Map<String, Object> selectionRule,
                                      LocalDate benchmarkEffectiveFrom,
                                      LocalDate targetStartDate) {
        LocalDate configured = configuredStartDate(selectionRule);
        LocalDate resolved = earlier(benchmarkEffectiveFrom, targetStartDate);
        return configured == null ? resolved : earlier(configured, resolved);
    }

    static LocalDate memberStartDate(LocalDate requestedStartDate,
                                     List<Map<String, Object>> records) {
        LocalDate firstRecordDate = records.stream()
                .map(QuantResearchHistoryPolicy::recordDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(requestedStartDate);
        if (requestedStartDate == null) return firstRecordDate;
        return firstRecordDate.isAfter(requestedStartDate)
                ? firstRecordDate
                : requestedStartDate;
    }

    private static LocalDate recordDate(Map<String, Object> record) {
        for (String key : List.of("data_date", "trade_date", "date")) {
            Object value = record.get(key);
            if (value == null || String.valueOf(value).isBlank()) continue;
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        }
        return null;
    }

    private static LocalDate configuredStartDate(Map<String, Object> selectionRule) {
        Object value = selectionRule.get("historyStartDate");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("研究资产池 historyStartDate 必须使用 yyyy-MM-dd", exception);
        }
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }
}
