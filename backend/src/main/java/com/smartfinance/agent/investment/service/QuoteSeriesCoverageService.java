package com.smartfinance.agent.investment.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class QuoteSeriesCoverageService {
    private static final int MARKET_CLOSURE_TOLERANCE_DAYS = 14;
    public record Coverage(LocalDate requestedStartDate, LocalDate historyStartDate,
                           LocalDate historyEndDate, LocalDate targetDate, long observations,
                           String status, String reason) {
        public boolean complete() { return "COMPLETE".equals(status); }
    }

    private final JdbcTemplate db;

    public QuoteSeriesCoverageService(JdbcTemplate db) {
        this.db = db;
    }

    public Coverage find(long productId, String adjustType, String datasetType) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT requested_start_date,history_start_date,history_end_date,target_date,"
                        + "observations,status,reason FROM product_quote_coverage "
                        + "WHERE product_id=? AND frequency='DAY' AND adjust_type=? AND dataset_type=?",
                productId, adjustType, datasetType);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Coverage(day(row.get("requested_start_date")), day(row.get("history_start_date")),
                day(row.get("history_end_date")), day(row.get("target_date")),
                ((Number) row.get("observations")).longValue(), String.valueOf(row.get("status")),
                row.get("reason") == null ? null : String.valueOf(row.get("reason")));
    }

    public Coverage record(long productId, String adjustType, String datasetType,
                           LocalDate expectedStart, LocalDate target, boolean qualityComplete,
                           String provider, String datasetVersion, String incompleteReason) {
        if (!List.of("PRICE", "NAV", "TOTAL_RETURN_INDEX").contains(datasetType)) {
            throw new IllegalArgumentException("不支持的数据序列");
        }
        String condition = "TOTAL_RETURN_INDEX".equals(datasetType)
                ? " AND total_return_index>0" : "";
        Map<String, Object> stats = db.queryForMap(
                "SELECT MIN(trade_date) AS first_date,MAX(trade_date) AS last_date,COUNT(*) AS observations "
                        + "FROM product_daily_quote WHERE product_id=? AND adjust_type=?" + condition,
                productId, adjustType);
        LocalDate first = day(stats.get("first_date"));
        LocalDate last = day(stats.get("last_date"));
        long count = ((Number) stats.get("observations")).longValue();
        if ("TOTAL_RETURN_INDEX".equals(datasetType)) {
            long navCount = db.queryForObject(
                    "SELECT COUNT(*) FROM product_daily_quote WHERE product_id=? AND adjust_type=?",
                    Long.class, productId, adjustType);
            if (count < navCount) {
                qualityComplete = false;
                incompleteReason = "TOTAL_RETURN_MISSING";
            }
        }
        Coverage old = find(productId, adjustType, datasetType);
        LocalDate requestedStart = old != null && old.requestedStartDate() != null
                && (expectedStart == null || old.requestedStartDate().isBefore(expectedStart))
                ? old.requestedStartDate() : expectedStart;
        LocalDate requiredTarget = old != null && old.targetDate() != null
                && (target == null || old.targetDate().isAfter(target)) ? old.targetDate() : target;
        boolean sourceLimited = first != null && requestedStart != null
                && first.isAfter(requestedStart.plusDays(MARKET_CLOSURE_TOLERANCE_DAYS));
        boolean targetReached = last != null && requiredTarget != null
                && !last.isBefore(requiredTarget.minusDays(MARKET_CLOSURE_TOLERANCE_DAYS));
        String status = count == 0 ? "INCOMPLETE" : sourceLimited ? "SOURCE_LIMITED"
                : qualityComplete && targetReached ? "COMPLETE" : "INCOMPLETE";
        String reason = count == 0 ? "NO_DATA" : sourceLimited ? "PROVIDER_START_AFTER_REQUEST"
                : "COMPLETE".equals(status) ? null
                : !targetReached ? "PROVIDER_END_BEFORE_TARGET"
                : incompleteReason == null ? "QUALITY_INCOMPLETE" : incompleteReason;
        LocalDateTime now = LocalDateTime.now();
        Object[] values = {requestedStart, first, last, requiredTarget, count, status, reason,
                provider, datasetVersion, now, productId, adjustType, datasetType};
        String update = "UPDATE product_quote_coverage SET requested_start_date=?,history_start_date=?,"
                + "history_end_date=?,target_date=?,observations=?,status=?,reason=?,provider=?,"
                + "dataset_version=?,updated_at=? WHERE product_id=? AND adjust_type=? AND dataset_type=? "
                + "AND frequency='DAY'";
        if (db.update(update, values) == 0) {
            try {
                db.update("INSERT INTO product_quote_coverage(product_id,frequency,adjust_type,dataset_type,"
                                + "requested_start_date,history_start_date,history_end_date,target_date,observations,"
                                + "status,reason,provider,dataset_version,updated_at) "
                                + "VALUES(?,'DAY',?,?,?,?,?,?,?,?,?,?,?,?)",
                        productId, adjustType, datasetType, requestedStart, first, last, requiredTarget,
                        count, status, reason, provider, datasetVersion, now);
            } catch (DuplicateKeyException concurrentUpdate) {
                db.update(update, values);
            }
        }
        return new Coverage(requestedStart, first, last, requiredTarget, count, status, reason);
    }

    private static LocalDate day(Object value) {
        return value == null ? null : LocalDate.parse(String.valueOf(value).substring(0, 10));
    }
}
