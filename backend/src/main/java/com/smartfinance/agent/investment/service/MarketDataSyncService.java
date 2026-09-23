package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataSyncService.class);
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final InvestmentProductMapper products;
    private final AnalysisServiceClient analysis;
    private final InvestmentDataQualityService quality;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final int historyYears;
    private final int chunkDays;

    public MarketDataSyncService(JdbcTemplate db, InvestmentProductMapper products,
                                 AnalysisServiceClient analysis, InvestmentDataQualityService quality,
                                 PlatformTransactionManager manager,
                                 @Value("${market-data.sync.enabled:true}") boolean enabled,
                                 @Value("${market-data.history-years:10}") int historyYears,
                                 @Value("${market-data.chunk-days:90}") int chunkDays) {
        this.db = db;
        this.products = products;
        this.analysis = analysis;
        this.quality = quality;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
        if (historyYears < 1 || historyYears > 30 || chunkDays < 7 || chunkDays > 365)
            throw new IllegalArgumentException("市场数据同步窗口配置无效");
        this.historyYears = historyYears;
        this.chunkDays = chunkDays;
    }

    @Scheduled(cron = "${market-data.catalog.cron:0 15 9 * * *}", zone = "Asia/Shanghai")
    public void refreshCatalogs() {
        if (!enabled) return;
        for (String market : List.of("CN_A", "CN_ETF", "US", "INDEX")) {
            try { importCatalog(market); }
            catch (RuntimeException failure) { log.warn("Market catalog {} failed", market, failure); }
        }
    }

    public int importCatalog(String market) {
        if (!Set.of("CN_A", "CN_ETF", "US", "INDEX").contains(market))
            throw new IllegalArgumentException("不支持的市场目录");
        setCatalogState(market, "RUNNING", 0, null);
        try {
            List<Map<String, Object>> entries = analysis.marketCatalog(market);
            if (entries.isEmpty()) throw new IllegalStateException("市场目录为空");
            Map<String, InvestmentProduct> existing = new HashMap<>();
            for (InvestmentProduct product : products.selectList(null))
                existing.put(key(product.getProductType(), product.getMarket(), product.getCode()), product);
            List<Map<String, Object>> missing = new java.util.ArrayList<>();
            Set<String> seen = new HashSet<>(existing.keySet());
            for (Map<String, Object> entry : entries) {
                String type = value(entry, "productType");
                String productMarket = value(entry, "market");
                String code = value(entry, "code");
                String name = value(entry, "name");
                String currency = value(entry, "currency");
                if (type == null || productMarket == null || code == null || name == null || currency == null)
                    continue;
                String identity = key(type, productMarket, code);
                if (seen.add(identity)) missing.add(entry);
            }
            for (int from = 0; from < missing.size(); from += 200) {
                List<Map<String, Object>> batch = missing.subList(from, Math.min(from + 200, missing.size()));
                db.batchUpdate("INSERT INTO investment_product(product_type,market,code,name,currency,status,"
                        + "exchange_code,source_metadata) VALUES(?,?,?,?,?,?,?,?)", new BatchPreparedStatementSetter() {
                    @Override public int getBatchSize() { return batch.size(); }
                    @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
                        Map<String, Object> item = batch.get(index);
                        Object[] values = {value(item, "productType"), value(item, "market"), value(item, "code"),
                                value(item, "name"), value(item, "currency"), "ACTIVE",
                                value(item, "exchange"), value(item, "source")};
                        for (int column = 0; column < values.length; column++) statement.setObject(column + 1, values[column]);
                    }
                });
            }
            queueMissingBackfills();
            setCatalogState(market, "SUCCEEDED", entries.size(), null);
            return missing.size();
        } catch (RuntimeException failure) {
            setCatalogState(market, "FAILED", 0, failure.getMessage());
            throw failure;
        }
    }

    private void queueMissingBackfills() {
        List<Long> ids = db.queryForList("SELECT p.id FROM investment_product p "
                + "LEFT JOIN market_data_job j ON j.product_id=p.id AND j.job_type='BACKFILL' "
                + "WHERE p.status='ACTIVE' AND p.market IN ('SSE','SZSE','BSE','NYSE','NASDAQ','AMEX','FUND_CN','CN_INDEX') "
                + "AND j.id IS NULL", Long.class);
        LocalDate target = LocalDate.now(CN_ZONE);
        LocalDate start = target.minusYears(historyYears);
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        for (int from = 0; from < ids.size(); from += 200) {
            List<Long> batch = ids.subList(from, Math.min(from + 200, ids.size()));
            db.batchUpdate("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                    + "attempt_count,updated_at) VALUES(?,'BACKFILL','QUEUED',?,?,0,?)",
                    batch.stream().map(id -> new Object[]{id, start, target, now}).toList());
        }
    }

    public void queueBackfill(long productId) {
        InvestmentProduct product = products.selectById(productId);
        if (product == null) throw new IllegalArgumentException("产品不存在");
        LocalDate target = LocalDate.now(CN_ZONE);
        LocalDate start = target.minusYears(historyYears);
        if (product.getListingDate() != null && product.getListingDate().isAfter(start))
            start = product.getListingDate();
        enqueue(productId, "BACKFILL", start, target);
    }

    @Scheduled(cron = "${market-data.daily.cn-cron:0 30 17 * * MON-FRI}", zone = "Asia/Shanghai")
    public void queueChinaDaily() {
        if (enabled) queueDaily(Set.of("SSE", "SZSE", "BSE", "CN_INDEX"), LocalDate.now(CN_ZONE));
    }

    @Scheduled(cron = "${market-data.daily.us-cron:0 30 8 * * TUE-SAT}", zone = "Asia/Shanghai")
    public void queueUsDaily() {
        if (enabled) queueDaily(Set.of("NYSE", "NASDAQ", "AMEX"), LocalDate.now(CN_ZONE).minusDays(1));
    }

    @Scheduled(cron = "${market-data.daily.fund-cron:0 0 22 * * MON-FRI}", zone = "Asia/Shanghai")
    public void queueFundDaily() {
        if (enabled) queueDaily(Set.of("FUND_CN"), LocalDate.now(CN_ZONE));
    }

    public int queueDaily(Set<String> markets, LocalDate target) {
        if (markets.isEmpty()) return 0;
        String marks = String.join(",", java.util.Collections.nCopies(markets.size(), "?"));
        List<Map<String, Object>> candidates = db.queryForList(
                "SELECT p.id,p.history_end_date FROM investment_product p "
                        + "LEFT JOIN market_data_job b ON b.product_id=p.id AND b.job_type='BACKFILL' "
                        + "WHERE p.status='ACTIVE' AND p.market IN (" + marks + ") "
                        + "AND (b.id IS NULL OR b.status='SUCCEEDED')",
                markets.toArray());
        int queued = 0;
        for (Map<String, Object> row : candidates) {
            long id = ((Number) row.get("id")).longValue();
            LocalDate last = day(row.get("history_end_date"));
            if (last == null) { queueBackfill(id); continue; }
            LocalDate start = last.plusDays(1);
            if (start.isAfter(target)) continue;
            enqueue(id, "DAILY_UPDATE", start, target);
            queued++;
        }
        return queued;
    }

    private void enqueue(long productId, String type, LocalDate start, LocalDate target) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,status FROM market_data_job WHERE product_id=? AND job_type=?", productId, type);
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        if (rows.isEmpty()) {
            try {
                db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,attempt_count,updated_at) "
                                + "VALUES(?,?,?,?,?,0,?)", productId, type, "QUEUED", start, target, now);
            } catch (org.springframework.dao.DuplicateKeyException concurrentQueue) {
                // The existing job will be reconsidered on the next schedule.
            }
        } else if (!Set.of("RUNNING", "QUEUED", "RETRY_WAIT").contains(rows.get(0).get("status"))) {
            db.update("UPDATE market_data_job SET status='QUEUED',start_date=?,target_date=?,"
                            + "checkpoint_date=NULL,attempt_count=0,next_retry_at=NULL,last_error=NULL,updated_at=? WHERE id=?",
                    start, target, now, rows.get(0).get("id"));
        }
    }

    @Scheduled(initialDelayString = "${market-data.worker.initial-delay-ms:60000}",
            fixedDelayString = "${market-data.worker.poll-ms:2000}")
    public void processNext() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM market_data_job WHERE (status='QUEUED' OR "
                        + "(status='RETRY_WAIT' AND next_retry_at<=?) OR "
                        + "(status='RUNNING' AND lease_until<=?)) ORDER BY updated_at,id LIMIT 1", now, now);
        if (rows.isEmpty()) return;
        Map<String, Object> job = rows.get(0);
        long id = ((Number) job.get("id")).longValue();
        int claimed = db.update("UPDATE market_data_job SET status='RUNNING',lease_until=?,updated_at=? "
                        + "WHERE id=? AND status=? AND updated_at=?", now.plusMinutes(10), now,
                id, job.get("status"), job.get("updated_at"));
        if (claimed != 1) return;
        try {
            processChunk(job);
        } catch (RuntimeException failure) {
            int attempt = ((Number) job.get("attempt_count")).intValue() + 1;
            long delay = Math.min(3600, 30L << Math.min(attempt, 7));
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            db.update("UPDATE market_data_job SET status=?,attempt_count=?,next_retry_at=?,lease_until=NULL,"
                            + "last_error=?,updated_at=? WHERE id=? AND status='RUNNING'",
                    attempt >= 6 ? "FAILED" : "RETRY_WAIT", attempt,
                    now.plusSeconds(delay), message.substring(0, Math.min(500, message.length())), now, id);
            log.warn("Market data job {} failed on attempt {}", id, attempt, failure);
        }
    }

    private void processChunk(Map<String, Object> job) {
        long jobId = ((Number) job.get("id")).longValue();
        InvestmentProduct product = products.selectById(((Number) job.get("product_id")).longValue());
        if (product == null) throw new IllegalStateException("同步产品不存在");
        LocalDate checkpoint = day(job.get("checkpoint_date"));
        LocalDate start = checkpoint == null ? day(job.get("start_date")) : checkpoint.plusDays(1);
        LocalDate target = day(job.get("target_date"));
        if (start.isAfter(target)) { finish(jobId); return; }
        // This fund provider returns its complete NAV history for every request.
        // Consume it once per backfill instead of downloading it again for each chunk.
        LocalDate end = "FUND_CN".equals(product.getMarket()) ? target :
                start.plusDays(chunkDays - 1).isBefore(target)
                        ? start.plusDays(chunkDays - 1) : target;
        Map<String, Object> response = analysis.marketDailyQuotes(product, start, end, "NONE");
        @SuppressWarnings("unchecked") List<Map<String, Object>> records =
                (List<Map<String, Object>>) response.get("records");
        if (records == null) throw new IllegalStateException("数据源未返回日线列表");
        LocalDate chunkEnd = end;
        transaction.executeWithoutResult(status -> {
            if (!records.isEmpty()) insertMissingQuotes(product.getId(), records,
                    value(response, "provider"), value(response, "adapterVersion"));
            db.update("UPDATE investment_product SET history_start_date=(SELECT MIN(trade_date) FROM "
                            + "product_daily_quote WHERE product_id=? AND adjust_type='NONE'),"
                            + "history_end_date=(SELECT MAX(trade_date) FROM product_daily_quote WHERE product_id=? "
                            + "AND adjust_type='NONE') WHERE id=?", product.getId(), product.getId(), product.getId());
            db.update("UPDATE market_data_job SET checkpoint_date=?,status=?,attempt_count=0,"
                            + "lease_until=NULL,next_retry_at=NULL,last_error=NULL,updated_at=? WHERE id=? AND status='RUNNING'",
                    chunkEnd, chunkEnd.equals(target) ? "SUCCEEDED" : "QUEUED", LocalDateTime.now(CN_ZONE), jobId);
        });
        if (chunkEnd.equals(target) && !records.isEmpty()
                && Set.of("STOCK", "MUTUAL_FUND", "FUND").contains(product.getProductType())) {
            try {
                quality.resolve(product, start, chunkEnd, true);
            } catch (RuntimeException unavailable) {
                log.warn("Market data quality evaluation unavailable for product {}", product.getId(), unavailable);
            }
        }
    }

    private int insertMissingQuotes(long productId, List<Map<String, Object>> records,
                                    String provider, String adapterVersion) {
        LocalDate first = records.stream().map(row -> day(row.get("data_date"))).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = records.stream().map(row -> day(row.get("data_date"))).max(LocalDate::compareTo).orElseThrow();
        Set<LocalDate> existing = new HashSet<>(db.queryForList(
                "SELECT trade_date FROM product_daily_quote WHERE product_id=? AND adjust_type='NONE' "
                        + "AND trade_date>=? AND trade_date<=?", LocalDate.class, productId, first, last));
        List<Map<String, Object>> missing = records.stream()
                .filter(row -> !existing.contains(day(row.get("data_date"))))
                .filter(row -> row.get("close") != null || row.get("nav") != null).toList();
        LocalDateTime syncedAt = LocalDateTime.now(CN_ZONE);
        db.batchUpdate("INSERT INTO product_daily_quote(product_id,trade_date,open_price,high_price,low_price,"
                        + "close_price,previous_close,volume,amount,turnover_rate,volume_ratio,amplitude,"
                        + "change_amount,change_percent,total_return_index,adjust_type,source,adapter_version,synced_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return missing.size(); }
            @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
                Map<String, Object> row = missing.get(index);
                Object[] values = {productId, day(row.get("data_date")), decimal(row.get("open")),
                        decimal(row.get("high")), decimal(row.get("low")),
                        decimal(row.get("close") == null ? row.get("nav") : row.get("close")),
                        decimal(row.get("previous_close")),
                        decimal(row.get("volume")), decimal(row.get("amount")), decimal(row.get("turnover_rate")),
                        decimal(row.get("volume_ratio")), decimal(row.get("amplitude")),
                        decimal(row.get("change_amount")), decimal(row.get("change_percent")),
                        decimal(row.get("total_return_index")), "NONE", provider, adapterVersion, syncedAt};
                for (int column = 0; column < values.length; column++) statement.setObject(column + 1, values[column]);
            }
        });
        return missing.size();
    }

    private void finish(long jobId) {
        db.update("UPDATE market_data_job SET status='SUCCEEDED',lease_until=NULL,updated_at=? WHERE id=?",
                LocalDateTime.now(CN_ZONE), jobId);
    }

    private void setCatalogState(String market, String status, int count, String error) {
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        int updated = db.update("UPDATE market_catalog_state SET status=?,product_count=?,last_synced_at=?,"
                + "last_error=? WHERE market=?", status, count, now,
                error == null ? null : error.substring(0, Math.min(500, error.length())), market);
        if (updated == 0) db.update("INSERT INTO market_catalog_state(market,status,product_count,last_synced_at,last_error) "
                + "VALUES(?,?,?,?,?)", market, status, count, now, error);
    }

    private static String key(String type, String market, String code) {
        return type + ":" + market + ":" + code;
    }
    private static String value(Map<String, Object> row, String key) {
        Object item = row.get(key);
        return item == null ? null : item.toString();
    }
    private static LocalDate day(Object value) {
        return value == null ? null : value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }
    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }
}
