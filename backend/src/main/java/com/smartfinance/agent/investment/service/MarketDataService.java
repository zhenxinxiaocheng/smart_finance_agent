package com.smartfinance.agent.investment.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketDataService {
    private static final Set<String> ADJUST_TYPES = Set.of("NONE", "QFQ", "HFQ");
    private final JdbcTemplate db;
    private final AnalysisServiceClient analysis;

    public MarketDataService(JdbcTemplate db, AnalysisServiceClient analysis) {
        this.db = db;
        this.analysis = analysis;
    }

    public Map<String, Object> products(String search, String market, String marketGroup, String assetType,
                                         String status, int page, int size) {
        if (page < 1 || size < 1 || size > 100) bad("分页范围无效");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (p.code LIKE ? ESCAPE '!' OR p.name LIKE ? ESCAPE '!')");
            String value = "%" + search.trim().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%";
            args.add(value); args.add(value);
        }
        if (market != null && !market.isBlank()) { where.append(" AND p.market=?"); args.add(market); }
        if (marketGroup != null && !marketGroup.isBlank()) {
            if ("CN_A".equals(marketGroup)) where.append(" AND p.market IN ('SSE','SZSE','BSE')");
            else if ("US".equals(marketGroup)) where.append(" AND p.market IN ('NYSE','NASDAQ')");
            else bad("市场分组无效");
        }
        if (assetType != null && !assetType.isBlank()) {
            if ("FUND".equals(assetType)) where.append(" AND p.product_type IN ('FUND','MUTUAL_FUND')");
            else { where.append(" AND p.product_type=?"); args.add(assetType); }
        }
        if (status != null && !status.isBlank()) { where.append(" AND p.status=?"); args.add(status); }
        long total = db.queryForObject("SELECT COUNT(*) FROM investment_product p" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add((long) (page - 1) * size);
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT p.id,p.code,p.name,p.market,p.exchange_code,p.product_type,p.currency,p.status,"
                        + "p.history_start_date,p.history_end_date FROM investment_product p" + where
                        + " ORDER BY p.market,p.code,p.id LIMIT ? OFFSET ?", pageArgs.toArray());
        if (!rows.isEmpty()) {
            List<Object> ids = rows.stream().map(row -> row.get("id")).toList();
            String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
            Map<Long, Map<String, Object>> coverage = new HashMap<>();
            for (Map<String, Object> row : db.queryForList(
                    "SELECT product_id,MIN(trade_date) AS history_start_date,MAX(trade_date) AS history_end_date,"
                            + "COUNT(*) AS observations FROM product_daily_quote WHERE adjust_type='NONE' AND product_id IN ("
                            + marks + ") GROUP BY product_id", ids.toArray())) {
                coverage.put(((Number) row.get("product_id")).longValue(), row);
            }
            for (Map<String, Object> row : rows) {
                Map<String, Object> found = coverage.get(((Number) row.get("id")).longValue());
                row.put("observations", found == null ? 0 : found.get("observations"));
                if (found != null) {
                    row.put("history_start_date", found.get("history_start_date"));
                    row.put("history_end_date", found.get("history_end_date"));
                }
                row.put("data_status", found == null ? "NO_DATA" : "AVAILABLE");
            }
        }
        return Map.of("items", rows.stream().map(MarketDataService::camel).toList(),
                "total", total, "page", page, "size", size);
    }

    public Map<String, Object> detail(long productId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM investment_product WHERE id=?", productId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品不存在");
        Map<String, Object> result = camel(rows.get(0));
        result.put("coverage", coverage(productId, "NONE"));
        List<Map<String, Object>> quality = db.queryForList("SELECT quality_status,decision,provider,adapter_version,"
                + "adjust_type,evaluated_at FROM investment_data_quality_snapshot WHERE product_type=? AND market=? "
                + "AND code=? ORDER BY evaluated_at DESC LIMIT 1", rows.get(0).get("product_type"),
                rows.get(0).get("market"), rows.get(0).get("code"));
        result.put("quality", quality.isEmpty() ? Map.of("status", "NOT_EVALUATED") : camel(quality.get(0)));
        return result;
    }

    public Map<String, Object> getCoverage(long productId, String adjustType) {
        return coverage(productId, adjustType);
    }

    public Map<String, Object> coverage(long productId, String adjustType) {
        String adjust = adjustment(adjustType);
        List<Map<String, Object>> products = db.queryForList(
                "SELECT id,market,product_type FROM investment_product WHERE id=?", productId);
        if (products.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "产品不存在");
        Map<String, Object> product = products.get(0);
        Map<String, Object> stats = db.queryForMap(
                "SELECT MIN(trade_date) AS history_start_date,MAX(trade_date) AS history_end_date,"
                        + "COUNT(*) AS observations,MAX(synced_at) AS last_synced_at "
                        + "FROM product_daily_quote WHERE product_id=? AND adjust_type=?", productId, adjust);
        List<Map<String, Object>> latest = db.queryForList(
                "SELECT source,adapter_version FROM product_daily_quote WHERE product_id=? AND adjust_type=? "
                        + "ORDER BY trade_date DESC LIMIT 1", productId, adjust);
        LocalDate start = date(stats.get("history_start_date"));
        LocalDate end = date(stats.get("history_end_date"));
        Long missing = null;
        Double ratio = null;
        if (start != null && end != null && Set.of("SSE", "SZSE", "BSE", "FUND_CN").contains(product.get("market"))) {
            try {
                long expected = 0;
                long observed = 0;
                Set<LocalDate> storedDates = new java.util.HashSet<>(db.queryForList(
                        "SELECT trade_date FROM product_daily_quote WHERE product_id=? AND adjust_type=?",
                        LocalDate.class, productId, adjust));
                for (int year = start.getYear(); year <= end.getYear(); year++) {
                    for (LocalDate tradingDate : analysis.aShareTradingDates(year)) {
                        if (!tradingDate.isBefore(start) && !tradingDate.isAfter(end)) {
                            expected++;
                            if (storedDates.contains(tradingDate)) observed++;
                        }
                    }
                }
                if (expected > 0) {
                    missing = expected - observed;
                    ratio = (double) observed / expected;
                }
            } catch (RuntimeException unavailable) {
                // No weekday estimate when the provider calendar is unavailable.
            }
        }
        Map<String, Object> result = camel(stats);
        result.put("productId", productId);
        result.put("adjustType", adjust);
        result.put("source", latest.isEmpty() ? null : latest.get(0).get("source"));
        result.put("adapterVersion", latest.isEmpty() ? null : latest.get(0).get("adapter_version"));
        result.put("missingTradingDays", missing);
        result.put("coverageRatio", ratio);
        result.put("coverageStatus", start == null ? "NO_DATA" : ratio == null ? "CALENDAR_UNAVAILABLE" :
                missing == 0 ? "COMPLETE" : "GAPS");
        List<String> quality = db.queryForList("SELECT quality_status FROM investment_data_quality_snapshot "
                        + "WHERE product_type=? AND market=? AND code=(SELECT code FROM investment_product WHERE id=?) "
                        + "AND adjust_type=? ORDER BY evaluated_at DESC LIMIT 1", String.class,
                product.get("product_type"), product.get("market"), productId, adjust);
        result.put("dataQualityStatus", quality.isEmpty() ? "NOT_EVALUATED" : quality.get(0));
        return result;
    }

    public Map<Long, List<Map<String, Object>>> getDailySeries(List<Long> productIds,
                    LocalDate startDate, LocalDate endDate, String adjustType) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100
                || startDate == null || endDate == null || startDate.isAfter(endDate)) bad("日线查询范围无效");
        String adjust = adjustment(adjustType);
        String marks = String.join(",", Collections.nCopies(productIds.size(), "?"));
        List<Object> args = new ArrayList<>(productIds);
        args.add(startDate); args.add(endDate); args.add(adjust);
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        productIds.forEach(id -> result.put(id, new ArrayList<>()));
        for (Map<String, Object> row : db.queryForList(
                "SELECT product_id,trade_date,open_price,high_price,low_price,close_price,previous_close,"
                        + "volume,amount,total_return_index,source,adjust_type FROM product_daily_quote WHERE product_id IN ("
                        + marks + ") AND trade_date>=? AND trade_date<=? AND adjust_type=? "
                        + "ORDER BY product_id,trade_date", args.toArray())) {
            long id = ((Number) row.get("product_id")).longValue();
            result.get(id).add(camel(row));
        }
        return result;
    }

    public Map<String, Object> getUniverseMembers(long userId, String universeId, LocalDate asOfDate) {
        List<Map<String, Object>> snapshots = db.queryForList(
                "SELECT id,as_of_date,capability,revision FROM quant_universe_snapshot "
                        + "WHERE user_id=? AND universe_id=? AND as_of_date<=? ORDER BY as_of_date DESC,revision DESC LIMIT 1",
                userId, universeId, asOfDate);
        if (snapshots.isEmpty()) return Map.of("capability", "UNAVAILABLE", "productIds", List.of());
        Map<String, Object> snapshot = snapshots.get(0);
        String capability = String.valueOf(snapshot.get("capability"));
        LocalDate captured = date(snapshot.get("as_of_date"));
        if ("CURRENT_SNAPSHOT".equals(capability) && !captured.equals(asOfDate))
            return Map.of("capability", "UNAVAILABLE", "productIds", List.of());
        List<Long> ids = db.queryForList("SELECT product_id FROM quant_universe_member WHERE snapshot_id=? ORDER BY product_id",
                Long.class, snapshot.get("id"));
        return Map.of("capability", capability, "asOfDate", captured,
                "revision", snapshot.get("revision"), "productIds", ids);
    }

    public List<Map<String, Object>> preview(long productId, int limit) {
        if (limit < 1 || limit > 500) bad("预览范围无效");
        return db.queryForList("SELECT trade_date,close_price,volume,source,adjust_type FROM product_daily_quote "
                + "WHERE product_id=? AND adjust_type='NONE' ORDER BY trade_date DESC LIMIT ?", productId, limit)
                .stream().map(MarketDataService::camel).toList();
    }

    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> groups = db.queryForList("SELECT market,product_type,COUNT(*) AS product_count,"
                + "MIN(history_start_date) AS history_start_date,MAX(history_end_date) AS latest_data_date "
                + "FROM investment_product GROUP BY market,product_type ORDER BY market,product_type")
                .stream().map(MarketDataService::camel).toList();
        Map<String, Long> today = new HashMap<>();
        LocalDate now = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (Map<String, Object> row : db.queryForList("SELECT p.market,p.product_type,COUNT(DISTINCT q.product_id) AS count "
                + "FROM product_daily_quote q JOIN investment_product p ON p.id=q.product_id "
                + "WHERE q.synced_at>=? AND q.synced_at<? GROUP BY p.market,p.product_type",
                now.atStartOfDay(), now.plusDays(1).atStartOfDay()))
            today.put(row.get("market") + ":" + row.get("product_type"), ((Number) row.get("count")).longValue());
        Map<String, String> tasks = new HashMap<>();
        for (Map<String, Object> row : db.queryForList("SELECT p.market,p.product_type,j.status,COUNT(*) AS count "
                + "FROM market_data_job j JOIN investment_product p ON p.id=j.product_id "
                + "GROUP BY p.market,p.product_type,j.status")) {
            String key = row.get("market") + ":" + row.get("product_type");
            String state = String.valueOf(row.get("status"));
            if (!tasks.containsKey(key) || Set.of("RUNNING", "RETRY_WAIT", "FAILED").contains(state)) tasks.put(key, state);
        }
        for (Map<String, Object> row : groups) {
            String key = row.get("market") + ":" + row.get("productType");
            row.put("todaySynced", today.getOrDefault(key, 0L));
            row.put("dataQualityStatus", "NOT_EVALUATED");
            row.put("syncTaskStatus", tasks.getOrDefault(key, "NOT_SCHEDULED"));
        }
        Map<String, Long> evaluated = new HashMap<>();
        for (Map<String, Object> row : db.queryForList("SELECT market,product_type,COUNT(DISTINCT code) AS count "
                + "FROM investment_data_quality_snapshot GROUP BY market,product_type"))
            evaluated.put(row.get("market") + ":" + row.get("product_type"),
                    ((Number) row.get("count")).longValue());
        for (Map<String, Object> row : groups) {
            long count = evaluated.getOrDefault(row.get("market") + ":" + row.get("productType"), 0L);
            row.put("dataQualityStatus", count == 0 ? "NOT_EVALUATED" :
                    count >= ((Number) row.get("productCount")).longValue() ? "EVALUATED" : "PARTIAL");
        }
        return groups;
    }

    public List<Map<String, Object>> jobs() {
        return db.queryForList("SELECT j.id,j.product_id,p.code,p.market,j.job_type,j.status,j.checkpoint_date,"
                + "j.target_date,j.attempt_count,j.updated_at FROM market_data_job j "
                + "JOIN investment_product p ON p.id=j.product_id ORDER BY j.updated_at DESC LIMIT 50")
                .stream().map(MarketDataService::camel).toList();
    }

    private static String adjustment(String value) {
        String result = value == null ? "NONE" : value.toUpperCase();
        if (!ADJUST_TYPES.contains(result)) bad("复权类型无效");
        return result;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : value instanceof LocalDate day ? day : LocalDate.parse(value.toString());
    }

    private static Map<String, Object> camel(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            StringBuilder converted = new StringBuilder();
            boolean upper = false;
            for (char c : key.toCharArray()) {
                if (c == '_') upper = true;
                else { converted.append(upper ? Character.toUpperCase(c) : c); upper = false; }
            }
            result.put(converted.toString(), value);
        });
        return result;
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
