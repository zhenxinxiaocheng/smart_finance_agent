package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Market Data：只负责把市场目录落成产品，并调度原有历史行情能力。
 *
 * <p>产品目录归属由 catalog_market 保存；历史行情、质量校验和落库复用 Investment 链路。
 */
@Service
public class MarketDataSyncService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataSyncService.class);
    private static final ZoneId CN_ZONE = ZoneId.of("Asia/Shanghai");

    private static final List<String> CATALOGS = List.of("CN_A", "CN_ETF", "US", "INDEX", "FUND_CN");
    private static final List<String> JOB_GROUPS = List.of(
            "j.job_type='DAILY_UPDATE'", "j.job_type='BACKFILL'",
            "j.job_type NOT IN ('BACKFILL','DAILY_UPDATE')");
    private static final Set<String> ACTIVE_JOBS = Set.of("RUNNING", "QUEUED", "RETRY_WAIT");
    private static final Set<String> US_MARKETS = Set.of("NYSE", "NASDAQ", "AMEX", "US_INDEX");
    private static final String ANALYSIS_QUEUE_FAILED = "ANALYSIS_QUEUE_FAILED";
    private static final String REQUIRED_SERIES_MISSING = "REQUIRED_SERIES_MISSING";

    private final JdbcTemplate db;
    private final InvestmentProductMapper products;
    private final AnalysisServiceClient analysis;
    private final InvestmentHistoryPreparationService historyPreparation;
    private final boolean enabled;
    private final int historyYears;
    private final int chunkDays;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final InvestmentDataJobService tasks;
    private final TransactionTemplate claimTransaction;
    private InvestmentResearchDataService research;
    private ThreadPoolTaskExecutor executor;
    private final AtomicInteger executing = new AtomicInteger();
    private int nextGroup;
    private MarketDataRequirementService requirements;
    private MarketDataDemandService demand;
    private InvestmentQuoteAvailabilityService availability;
    @Autowired public void setAvailability(InvestmentQuoteAvailabilityService availability) { this.availability=availability; }
    @Autowired
    public void setDemand(MarketDataDemandService demand) { this.demand = demand; }
    private com.smartfinance.agent.investment.config.MarketDataScopeProperties scopeProperties=new com.smartfinance.agent.investment.config.MarketDataScopeProperties();
    @Autowired public void setScopeProperties(com.smartfinance.agent.investment.config.MarketDataScopeProperties properties){this.scopeProperties=properties;}
    @Value("${market-data.worker.foreground-quota:8}") private int foregroundQuota=8;
    private int foregroundStreak;

    @Autowired
    public void setRequirements(MarketDataRequirementService requirements) { this.requirements = requirements; }
    public void setFullLibraryEnabled(boolean enabled) { this.scopeProperties.setEnabled(enabled); }

    private LocalDate priceTarget(InvestmentProduct product,LocalDate requested) {
        return availability==null ? requested : availability.target(product,requested,java.time.Instant.now());
    }

    private boolean fullScope(String catalog, String dataset) {
        return scopeProperties.allows(catalog,dataset);
    }
    private static String catalog(Map<String,Object> row) {
        String saved=value(row,"catalog_market");
        if(saved!=null)return saved;
        String market=value(row,"market");
        if("FUND_CN".equals(market))return "FUND_CN";
        if("CN_INDEX".equals(market)||"US_INDEX".equals(market))return "INDEX";
        if(Set.of("SSE","SZSE","BSE").contains(market==null?"":market))return "ETF".equals(value(row,"product_type"))?"CN_ETF":"CN_A";
        return "US";
    }
    private static String dataset(String jobType) {
        return Set.of("BACKFILL", "DAILY_UPDATE").contains(jobType) ? "PRICE" : jobType;
    }
    @Value("${market-data.worker.failed-retry-hours:6}")
    private int failedRetryHours = 6;
    @Value("${market-data.research.refresh-overlap-years:2}")
    private int researchOverlapYears = 2;

    @Autowired
    public void setResearch(InvestmentResearchDataService research) { this.research = research; }

    @Autowired
    public void setExecutor(@Qualifier("marketDataExecutor") ThreadPoolTaskExecutor executor) { this.executor = executor; }

    public MarketDataSyncService(JdbcTemplate db, InvestmentProductMapper products,
                                 AnalysisServiceClient analysis,
                                 InvestmentHistoryPreparationService historyPreparation,
                                 boolean enabled, int historyYears) {
        this(db, products, analysis, historyPreparation, enabled, historyYears, 3650, 600, 6, null, null);
    }

    @Autowired
    public MarketDataSyncService(JdbcTemplate db, InvestmentProductMapper products,
                                 AnalysisServiceClient analysis,
                                 InvestmentHistoryPreparationService historyPreparation,
                                 @Value("${market-data.sync.enabled:true}") boolean enabled,
                                 @Value("${market-data.history-years:50}") int historyYears,
                                 @Value("${market-data.worker.chunk-days:3650}") int chunkDays,
                                 @Value("${market-data.worker.lease-seconds:600}") int leaseSeconds,
                                 @Value("${market-data.worker.max-attempts:6}") int maxAttempts,
                                 InvestmentDataJobService tasks, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.products = products;
        this.analysis = analysis;
        this.historyPreparation = historyPreparation;
        this.enabled = enabled;
        if (historyYears < 1 || historyYears > 100 || chunkDays < 1 || chunkDays > 3650
                || leaseSeconds < 1 || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("市场数据同步窗口配置无效");
        }
        this.historyYears = historyYears;
        this.chunkDays = chunkDays;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.tasks = tasks;
        this.claimTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- 目录

    /** 启动后初始化目录，并补齐停机期间的增量，不阻塞个人资产调度。 */
    @Scheduled(scheduler = "marketCatalogScheduler",
            initialDelayString = "${market-data.bootstrap.initial-delay-ms:10000}",
            fixedDelayString = "${market-data.bootstrap.interval-ms:21600000}")
    public void bootstrap() {
        if (!enabled) return;
        if (research != null) {
            try { research.refreshCapabilities(); }
            catch (RuntimeException error) { log.warn("Research capability refresh failed", error); }
        }
        for (String market : CATALOGS) {
            List<Map<String, Object>> states = db.queryForList(
                    "SELECT status,DATE(last_synced_at) AS synced_date FROM market_catalog_state WHERE market=?", market);
            if (states.isEmpty() || !"SUCCEEDED".equals(value(states.get(0), "status"))
                    || !LocalDate.now(CN_ZONE).equals(day(states.get(0).get("synced_date")))) importSafely(market);
        }
        for (String catalog : db.queryForList(
                "SELECT market FROM market_catalog_state WHERE status='SUCCEEDED'", String.class)) {
            if (!scopeProperties.isEnabled() || !scopeProperties.getMarkets().contains(catalog)) continue;
            queueCandidates(db.queryForList("SELECT * FROM investment_product "
                    + "WHERE status='ACTIVE' AND catalog_market=?", catalog), null);
        }
        reconcileDemand();
    }

    @Scheduled(scheduler = "marketCatalogScheduler", cron = "${market-data.catalog.cron:0 15 9 * * *}", zone = "Asia/Shanghai")
    public void refreshCatalogs() {
        if (!enabled) return;
        CATALOGS.forEach(this::importSafely);
    }

    private void importSafely(String market) {
        try {
            importCatalog(market);
        } catch (RuntimeException failure) {
            log.warn("Market catalog {} failed", market, failure);
        }
    }

    /** 拉取一个市场目录并落库，返回新增产品数。 */
    public int importCatalog(String market) {
        if (!CATALOGS.contains(market)) {
            throw new IllegalArgumentException("不支持的市场目录");
        }
        setCatalogState(market, "RUNNING", 0, null);
        try {
            List<Map<String, Object>> entries = analysis.marketCatalog(market);
            if (entries.isEmpty()) throw new IllegalStateException("市场目录为空");
            Set<String> existing = new HashSet<>();
            for (Map<String, Object> product : db.queryForList("SELECT product_type,market,code FROM investment_product")) {
                existing.add(key(value(product, "product_type"), value(product, "market"), value(product, "code")));
            }
            Set<String> seen = new HashSet<>();
            List<Object[]> metadata = new java.util.ArrayList<>();
            List<Map<String, Object>> missing = new java.util.ArrayList<>();
            for (Map<String, Object> entry : entries) {
                String type = value(entry, "productType");
                String productMarket = value(entry, "market");
                String code = value(entry, "code");
                if (type == null || productMarket == null || code == null
                        || value(entry, "name") == null || value(entry, "currency") == null) {
                    continue;
                }
                String key = key(type, productMarket, code);
                if (!seen.add(key)) continue;
                metadata.add(metadata(entry, market));
                if (!existing.contains(key)) missing.add(entry);
            }
            if (metadata.isEmpty()) throw new IllegalStateException("市场目录没有有效产品");
            for (int from = 0; from < metadata.size(); from += 200) {
                db.batchUpdate(metadataSql(), metadata.subList(from, Math.min(from + 200, metadata.size())));
            }
            int inserted = 0;
            for (int from = 0; from < missing.size(); from += 200) {
                List<Map<String, Object>> batch = missing.subList(from, Math.min(from + 200, missing.size()));
                for (Map<String, Object> item : batch) {
                    try {
                        inserted += db.update("INSERT INTO investment_product(product_type,market,code,name,currency,status,"
                                        + "exchange_code,source_metadata,catalog_market,listing_date) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                value(item, "productType"), value(item, "market"), value(item, "code"),
                                value(item, "name"), value(item, "currency"), "ACTIVE",
                                value(item, "exchange"), value(item, "source"), market, day(item.get("listingDate")));
                    } catch (DataAccessException concurrentImport) {
                        if (db.queryForObject("SELECT COUNT(*) FROM investment_product WHERE product_type=? AND market=? AND code=?",
                                Integer.class, value(item, "productType"), value(item, "market"), value(item, "code")) == 0) {
                            throw concurrentImport;
                        }
                        db.update(metadataSql(), metadata(item, market));
                    }
                }
            }
            setCatalogState(market, "SUCCEEDED", seen.size(), null);
            queueMissingBackfills(market);
            return inserted;
        } catch (RuntimeException failure) {
            setCatalogState(market, "FAILED", 0, failure.getMessage());
            throw failure;
        }
    }

    private static String metadataSql() {
        return "UPDATE investment_product SET name=?,currency=?,exchange_code=COALESCE(?,exchange_code),"
                + "source_metadata=COALESCE(?,source_metadata),catalog_market=?,"
                + "listing_date=COALESCE(?,listing_date),updated_at=? WHERE product_type=? AND market=? AND code=?";
    }

    private static Object[] metadata(Map<String, Object> entry, String catalog) {
        return new Object[]{value(entry, "name"), value(entry, "currency"), value(entry, "exchange"),
                value(entry, "source"), catalog, day(entry.get("listingDate")), LocalDateTime.now(CN_ZONE),
                value(entry, "productType"), value(entry, "market"), value(entry, "code")};
    }

    // ------------------------------------------------------------ 任务入队

    /** 目录内尚无本地历史的产品 → 首次全量回补。 */
    private void queueMissingBackfills(String catalog) {
        if (!fullScope(catalog, "PRICE")) return;
        List<Map<String, Object>> candidates = db.queryForList(
                "SELECT p.* FROM investment_product p "
                        + "LEFT JOIN market_data_job j ON j.product_id=p.id AND j.job_type='BACKFILL' "
                        + "WHERE p.status='ACTIVE' AND p.catalog_market=? "
                        + "AND (j.id IS NULL OR j.status='SKIPPED')", catalog);
        LocalDate requested = LocalDate.now(CN_ZONE);
        for (Map<String, Object> row : candidates) {
            InvestmentProduct product = candidateProduct(row);
            if (historyPreparation.incrementalStart(product) == null) {
                LocalDate target = priceTarget(product, requested);
                enqueue(product.getId(), "BACKFILL", initialStart(product, target), target);
            }
        }
    }

    /** 用户明确执行的全量修复。 */
    public void queueBackfill(long productId) {
        InvestmentProduct product = products.selectById(productId);
        if (product == null) throw new IllegalArgumentException("产品不存在");
        LocalDate target = priceTarget(product, LocalDate.now(CN_ZONE));
        enqueue(productId, "BACKFILL", initialStart(product, target), target, true);
    }

    private LocalDate initialStart(InvestmentProduct product, LocalDate target) {
        LocalDate start = target.minusYears(historyYears);
        LocalDate known = product.getListingDate() == null ? product.getInceptionDate() : product.getListingDate();
        return known != null && known.isAfter(start) ? known : start;
    }

    @Scheduled(scheduler = "marketCatalogScheduler", cron = "${market-data.daily.cn-cron:0 30 17 * * MON-FRI}", zone = "Asia/Shanghai")
    public void queueChinaDaily() {
        if (enabled) queueDaily(Set.of("SSE", "SZSE", "BSE", "CN_INDEX"), LocalDate.now(CN_ZONE));
    }

    @Scheduled(scheduler = "marketCatalogScheduler", cron = "${market-data.daily.us-cron:0 30 8 * * TUE-SAT}", zone = "Asia/Shanghai")
    public void queueUsDaily() {
        if (enabled) queueDaily(US_MARKETS, LocalDate.now(CN_ZONE).minusDays(1));
    }

    @Scheduled(scheduler = "marketCatalogScheduler", cron = "${market-data.daily.fund-cron:0 0 22 * * MON-FRI}", zone = "Asia/Shanghai")
    public void queueFundDaily() {
        if (enabled) queueDaily(Set.of("FUND_CN"), LocalDate.now(CN_ZONE));
    }

    /** 正常运行为增量：只从本地最后有效日期的次日开始。 */
    public int queueDaily(Set<String> markets, LocalDate target) {
        if (markets.isEmpty()) return 0;
        if (!scopeProperties.isEnabled()) return reconcileDemand();
        String marks = String.join(",", java.util.Collections.nCopies(markets.size(), "?"));
        List<Map<String, Object>> candidates = db.queryForList(
                "SELECT p.* FROM investment_product p "
                        + "WHERE p.status='ACTIVE' AND p.market IN (" + marks + ")",
                markets.toArray());
        return queueCandidates(candidates, target);
    }

    private int queueCandidates(List<Map<String, Object>> candidates, LocalDate suppliedTarget) {
        int queued = 0;
        for (Map<String, Object> row : candidates) {
            if (!scopeProperties.getMarkets().contains(catalog(row))) continue;
            InvestmentProduct product = candidateProduct(row);
            LocalDate target = suppliedTarget == null ? LocalDate.now(CN_ZONE)
                    .minusDays(US_MARKETS.contains(product.getMarket()) ? 1 : 0) : suppliedTarget;
            target=priceTarget(product,target);
            LocalDate start = historyPreparation.incrementalStart(product);
            queueResearch(product, target);
            if (!scopeProperties.getDatasets().contains("PRICE")) continue;
            if (start == null) {
                if (enqueue(product.getId(), "BACKFILL", initialStart(product, target), target)) queued++;
                continue;
            }
            if (start.isAfter(target)) continue;
            if (enqueue(product.getId(), "DAILY_UPDATE", start, target)) queued++;
        }
        return queued;
    }

    private void queueResearch(InvestmentProduct product, LocalDate target) {
        if (research == null || !scopeProperties.isEnabled()) return;
        var productRows=db.queryForList("SELECT * FROM investment_product WHERE id=?",product.getId());
        if(productRows.isEmpty()||!scopeProperties.getMarkets().contains(catalog(productRows.get(0))))return;
        Map<String, Map<String, Object>> existing = new java.util.HashMap<>();
        for (Map<String, Object> job : db.queryForList("SELECT job_type,status,updated_at,checkpoint_date "
                + "FROM market_data_job WHERE product_id=?", product.getId())) existing.put(value(job, "job_type"), job);
        for (Map<String, Object> definition : research.datasets(product)) {
            String dataset = value(definition, "dataset");
            if (!scopeProperties.getDatasets().contains(dataset)) continue;
            LocalDate start = initialStart(product, target);
            Map<String, Object> job = existing.get(dataset);
            if (job != null) {
                if (ACTIVE_JOBS.contains(value(job, "status"))) continue;
                LocalDateTime last = timestamp(job.get("updated_at"));
                int refreshDays = ((Number) definition.get("refreshDays")).intValue();
                if ("SUCCEEDED".equals(value(job, "status")) && last.plusDays(refreshDays).isAfter(LocalDateTime.now(CN_ZONE))) continue;
                LocalDate checkpoint = day(job.get("checkpoint_date"));
                if (checkpoint != null && research.yearly(dataset)) {
                    LocalDate overlap = checkpoint.minusYears(Math.max(1, researchOverlapYears) - 1L).withDayOfYear(1);
                    if (overlap.isAfter(start)) start = overlap;
                }
            }
            // 基金成立日期优先取真实资料；资料获取失败时仍允许回补已配置窗口。
            if (research.yearly(dataset) && product.getInceptionDate() == null
                    && existing.get("PROFILE") != null && ACTIVE_JOBS.contains(value(existing.get("PROFILE"), "status"))) continue;
            if (enqueue(product.getId(), dataset, start, target)) existing.put(dataset, Map.of("status", "QUEUED"));
        }
    }

    /** Reconcile business windows into the existing queue; never join disjoint intervals. */
    @Scheduled(scheduler = "marketCatalogScheduler", fixedDelayString = "${market-data.demand.reconcile-ms:60000}")
    public int reconcileDemand() {
        if (!enabled || requirements == null) return 0;
        if (demand != null) demand.reconcileOwners();
        int queued = 0;
        Set<String> visited = new HashSet<>();
        for (var requirement : requirements.active()) {
            long productId = ((Number) requirement.get("product_id")).longValue();
            String data = value(requirement, "dataset");
            if (!visited.add(productId + ":" + data)) continue;
            var rows = db.queryForList("SELECT * FROM investment_product WHERE id=?", productId);
            if (rows.isEmpty()) continue;
            var product = candidateProduct(rows.get(0));
            LocalDate target = demand==null ? priceTarget(product,LocalDate.now(CN_ZONE)) : demand.target(productId);
            var windows = requirements.missingWindows(productId, data, target);
            if (windows.isEmpty()) continue;
            var window = windows.get(0);
            if (enqueue(productId, "PRICE".equals(data) ? "BACKFILL" : data, window.start(), window.end())) queued++;
        }
        return queued;
    }

    private boolean enqueue(long productId, String type, LocalDate start, LocalDate target) {
        return enqueue(productId, type, start, target, false);
    }

    private boolean enqueue(long productId, String type, LocalDate start, LocalDate target, boolean explicitRepair) {
        if ("DAILY_UPDATE".equals(type) && !db.queryForList("SELECT id FROM market_data_job WHERE product_id=? "
                + "AND job_type='BACKFILL' AND status IN ('QUEUED','RUNNING','RETRY_WAIT')", productId).isEmpty()) {
            return false;
        }
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id,status,start_date,checkpoint_date,last_error,updated_at FROM market_data_job WHERE product_id=? AND job_type=?",
                productId, type);
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        if (rows.isEmpty()) {
            try {
                return db.update("INSERT INTO market_data_job(product_id,job_type,status,start_date,target_date,"
                                + "attempt_count,updated_at) VALUES(?,?,?,?,?,0,?)",
                        productId, type, "QUEUED", start, target, now) == 1;
            } catch (DataAccessException concurrentQueue) {
                if (db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE product_id=? AND job_type=?",
                        Integer.class, productId, type) == 0) throw concurrentQueue;
                return false;
            }
        }
        Map<String, Object> row = rows.get(0);
        if (ACTIVE_JOBS.contains(value(row, "status"))) return false;
        if ("FAILED".equals(value(row, "status")) && !explicitRepair
                && timestamp(row.get("updated_at")).plusHours(Math.max(1, failedRetryHours)).isAfter(now)) return false;
        String lastError = value(row, "last_error");
        if (("NO_NEW_DATA".equals(lastError) || "UNVERIFIED_WINDOW".equals(lastError)) && !explicitRepair
                && timestamp(row.get("updated_at")).plusHours(Math.max(1,failedRetryHours)).isAfter(now)) return false;
        boolean resume = "FAILED".equals(value(row, "status"))
                && !"NO_HISTORY_DATA".equals(value(row, "last_error"))
                && !REQUIRED_SERIES_MISSING.equals(value(row, "last_error"));
        return db.update("UPDATE market_data_job SET status='QUEUED',start_date=?,target_date=?,checkpoint_date=?,"
                        + "attempt_count=0,next_retry_at=NULL,last_error=NULL,lease_until=NULL,lease_token=NULL,updated_at=? "
                        + "WHERE id=? AND status=?",
                resume ? row.get("start_date") : start, target, resume ? row.get("checkpoint_date") : null,
                now, row.get("id"), row.get("status")) == 1;
    }

    // ------------------------------------------------------------ 任务执行

    @Scheduled(scheduler = "marketHistoryScheduler", initialDelayString = "${market-data.worker.initial-delay-ms:15000}",
            fixedDelayString = "${market-data.worker.poll-ms:2000}")
    public synchronized void processNext() {
        if (!enabled) return;
        int capacity = executor == null ? 1 : executor.getMaxPoolSize();
        for (int slot = executing.get(); slot < capacity; slot++) {
            Map<String, Object> job = nextJob();
            if (job == null) break;
            LocalDateTime now = LocalDateTime.now(CN_ZONE);
            String token = UUID.randomUUID().toString();
            boolean claimed = claimTransaction == null ? claim(job, token, now)
                    : Boolean.TRUE.equals(claimTransaction.execute(status -> claim(job, token, now)));
            if (!claimed) continue;
            if (executor == null) { executeJob(job, token); return; }
            executing.incrementAndGet();
            try { executor.execute(() -> { try { executeJob(job, token); } finally { executing.decrementAndGet(); } }); }
            catch (RuntimeException rejected) {
                executing.decrementAndGet();
                finish(((Number) job.get("id")).longValue(), token, "QUEUED", day(job.get("checkpoint_date")), null);
                break;
            }
        }
    }

    private Map<String, Object> nextJob() {
        if(requirements==null)return nextAvailable(false);
        if(foregroundStreak>=Math.max(1,foregroundQuota)) {
            foregroundStreak=0;
            Map<String,Object> bulk=nextAvailable(true);
            if(bulk!=null)return bulk;
        }
        LocalDateTime now=LocalDateTime.now(CN_ZONE);
        var foreground=db.queryForList("SELECT j.* FROM market_data_job j JOIN investment_product p ON p.id=j.product_id WHERE "
                +claimable()+" AND "+MarketDataRequirementService.businessDemand("j")+" ORDER BY "
                +MarketDataRequirementService.priorityExpression("j")+",j.updated_at,j.id LIMIT 1",
                now,now,now,requirements.now(),requirements.now());
        if(!foreground.isEmpty()){foregroundStreak++;var selected=foreground.get(0);selected.put("prefer_demand",true);return selected;}
        foregroundStreak=0;
        return nextAvailable(true);
    }
    private static String claimable() {
        return "(j.status='QUEUED' OR (j.status='RETRY_WAIT' AND j.next_retry_at<=?) OR "
                +"(j.status='RUNNING' AND (j.lease_until IS NULL OR j.lease_until<=?))) "
                +"AND NOT EXISTS(SELECT 1 FROM market_data_job other WHERE other.product_id=j.product_id "
                +"AND other.id<>j.id AND other.status='RUNNING' AND other.lease_until>?)";
    }
    private Map<String,Object> nextAvailable(boolean bulkOnly) {
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        // 每个市场的增量、历史回补和研究任务分别轮转，增量积压不会饿死回补。
        int groups = CATALOGS.size() * JOB_GROUPS.size();
        for (int checked = 0; checked < groups; checked++) {
            int group = nextGroup;
            nextGroup = (nextGroup + 1) % groups;
            String catalog = CATALOGS.get(group / JOB_GROUPS.size());
            String scope = "";
            var args = new java.util.ArrayList<Object>(List.of(catalog, now, now, now));
            if (requirements != null) {
                var eligible=MarketDataRequirementService.eligible("j","p",scopeProperties,requirements.now());
                scope=" AND "+eligible.sql();args.addAll(eligible.arguments());
                if(bulkOnly) {
                    scope+=" AND NOT "+MarketDataRequirementService.businessDemand("j");
                    args.add(requirements.now());
                }
            } else if (!scopeProperties.isEnabled()) return null;
            List<Map<String, Object>> rows = db.queryForList(
                "SELECT j.* FROM market_data_job j JOIN investment_product p ON p.id=j.product_id WHERE "
                        + MarketDataRequirementService.catalogExpression("p")+"=? "
                        + "AND " + JOB_GROUPS.get(group % JOB_GROUPS.size())
                        + " AND "+claimable()
                        + scope + " ORDER BY j.updated_at,j.id LIMIT 1", args.toArray());
            if (!rows.isEmpty()) return rows.get(0);
        }
        return null;
    }

    private void executeJob(Map<String, Object> job, String token) {
        try {
            run(job, token);
        } catch (RuntimeException failure) {
            LocalDateTime failedAt = LocalDateTime.now(CN_ZONE);
            int attempt = ((Number) job.get("attempt_count")).intValue() + 1;
            long delay = Math.min(3600, 30L << Math.min(attempt, 7));
            String message = failure instanceof AnalysisQueueException ? ANALYSIS_QUEUE_FAILED
                    : failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            db.update("UPDATE market_data_job SET status=?,attempt_count=?,next_retry_at=?,lease_until=NULL,lease_token=NULL,"
                            + "last_error=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                    attempt >= maxAttempts ? "FAILED" : "RETRY_WAIT", attempt,
                    attempt >= maxAttempts ? null : failedAt.plusSeconds(delay),
                    trim(message), failedAt, job.get("id"), token, failedAt);
            log.warn("Market data job {} failed on attempt {}", job.get("id"), attempt, failure);
        }
    }

    private boolean claim(Map<String, Object> job, String token, LocalDateTime now) {
        // 短事务锁住产品，避免不同job_type在多个实例上同时获得执行权。
        db.update("UPDATE investment_product SET id=id WHERE id=?", job.get("product_id"));
        Integer running = db.queryForObject("SELECT COUNT(*) FROM market_data_job WHERE product_id=? AND id<>? "
                        + "AND status='RUNNING' AND lease_until>?", Integer.class,
                job.get("product_id"), job.get("id"), now);
        if (running != null && running > 0) return false;
        int attempts = ((Number) job.get("attempt_count")).intValue();
        if ("RUNNING".equals(value(job, "status"))) attempts++;
        boolean exhausted = attempts >= maxAttempts;
        int updated = db.update("UPDATE market_data_job SET status=?,lease_until=?,lease_token=?,updated_at=?,"
                        + "attempt_count=?,next_retry_at=NULL,last_error=? "
                        + "WHERE id=? AND status=? AND updated_at=? AND start_date=? AND target_date=? "
                        + "AND (checkpoint_date=? OR (checkpoint_date IS NULL AND ? IS NULL)) "
                        + "AND (lease_token=? OR (lease_token IS NULL AND ? IS NULL)) "
                        + "AND (?<>'RUNNING' OR lease_until IS NULL OR lease_until<=?)",
                exhausted ? "FAILED" : "RUNNING", exhausted ? null : now.plusSeconds(leaseSeconds),
                exhausted ? null : token, now, attempts, exhausted ? "LEASE_EXPIRED" : job.get("last_error"),
                job.get("id"), job.get("status"), job.get("updated_at"),
                job.get("start_date"), job.get("target_date"), job.get("checkpoint_date"), job.get("checkpoint_date"),
                job.get("lease_token"), job.get("lease_token"), job.get("status"), now);
        if (updated != 1 || exhausted) return false;
        job.put("attempt_count", attempts);
        if (requirements != null) {
            var product = db.queryForMap("SELECT * FROM investment_product WHERE id=?", job.get("product_id"));
            String data = dataset(value(job, "job_type"));
            boolean full=fullScope(catalog(product),data);
            if (!full || Boolean.TRUE.equals(job.get("prefer_demand"))) {
                LocalDate target = demand==null ? priceTarget(candidateProduct(product),LocalDate.now(CN_ZONE)) : demand.target(((Number)job.get("product_id")).longValue());
                var windows = requirements.missingWindows(((Number) job.get("product_id")).longValue(), data, target);
                if (windows.isEmpty()) {
                    finish(((Number) job.get("id")).longValue(), token, "SKIPPED", null, "DEMAND_SATISFIED");
                    return false;
                }
                var window = windows.get(0);
                if(full){job.put("resume_full",true);job.put("full_checkpoint",job.get("checkpoint_date"));}
                else db.update("UPDATE market_data_job SET start_date=?,target_date=?,checkpoint_date=NULL WHERE id=? AND lease_token=?",
                            window.start(),window.end(),job.get("id"),token);
                job.put("scoped_demand",true);
                job.put("start_date",window.start());job.put("target_date",window.end());job.put("checkpoint_date",null);
            }
        }
        return true;
    }

    /** 复用个人持仓同一套历史行情能力，不再单独实现摄取/校验/落库。 */
    private void run(Map<String, Object> job, String token) {
        long jobId = ((Number) job.get("id")).longValue();
        InvestmentProduct product = products.selectById(((Number) job.get("product_id")).longValue());
        LocalDate checkpoint = day(job.get("checkpoint_date"));
        if (product == null) {
            finish(jobId, token, "SKIPPED", checkpoint, "产品不存在");
            return;
        }
        LocalDate start = checkpoint == null ? day(job.get("start_date")) : checkpoint.plusDays(1);
        LocalDate target = day(job.get("target_date"));
        String dataset = value(job, "job_type");
        if (!Set.of("BACKFILL", "DAILY_UPDATE").contains(dataset)) {
            if (research == null || !research.supports(dataset)) throw new IllegalStateException("研究数据集尚未就绪");
            if (start.isAfter(target)) { finish(jobId, token, "SUCCEEDED", checkpoint, null); return; }
            LocalDate end = research.yearly(dataset) ? LocalDate.of(start.getYear(), 12, 31) : target;
            if (end.isAfter(target)) end = target;
            Map<String, Object> response = research.collect(product, dataset, start, end);
            boolean complete = Boolean.TRUE.equals(response.get("complete"));
            LocalDate completedThrough = end;
            Supplier<Boolean> save = () -> {
                LocalDateTime now = LocalDateTime.now(CN_ZONE);
                db.update("UPDATE investment_product SET id=id WHERE id=?", product.getId());
                int owned = db.update("UPDATE market_data_job SET lease_until=lease_until WHERE id=? "
                        + "AND status='RUNNING' AND lease_token=? AND lease_until>?", jobId, token, now);
                if (owned != 1) return false;
                research.persist(product.getId(), dataset, response);
                if (complete && finish(jobId, token, completedThrough.isBefore(target) ? "QUEUED" : "SUCCEEDED", completedThrough, null) != 1)
                    throw new IllegalStateException("研究任务执行权已失效");
                if (complete && requirements != null) requirements.recordPrepared(product.getId(), dataset, start, completedThrough);
                return true;
            };
            boolean saved = claimTransaction == null ? save.get() : Boolean.TRUE.equals(claimTransaction.execute(tx -> save.get()));
            if (saved && !complete) throw new IllegalStateException("研究资料分区未完成: " + response.get("failedSections"));
            if (saved && complete && "PROFILE".equals(dataset)) {
                InvestmentProduct enriched = products.selectById(product.getId());
                if (enriched != null) queueResearch(enriched, target);
            }
            return;
        }
        if (start.isAfter(target)) {
            boolean missing = "BACKFILL".equals(value(job, "job_type")) && historyPreparation.incrementalStart(product) == null;
            finish(jobId, token, missing ? "FAILED" : "SUCCEEDED", checkpoint, missing ? REQUIRED_SERIES_MISSING : null);
            return;
        }
        boolean fund = "FUND_NAV_HISTORY".equals(jobTypeFor(product));
        boolean unknownOrigin = product.getListingDate() == null && product.getInceptionDate() == null;
        // 没有上市/成立日期时，先请求整个回补窗口，避免逐年重复请求上市前的空区间。
        boolean wholeWindow = fund || (unknownOrigin && "BACKFILL".equals(value(job, "job_type")));
        LocalDate end = wholeWindow ? target : start.plusDays(chunkDays - 1L);
        if (end.isAfter(target)) end = target;
        boolean forceRefresh = "BACKFILL".equals(value(job, "job_type")) && checkpoint == null;
        if(requirements!=null&&forceRefresh) {
            LocalDate next=historyPreparation.incrementalStart(product);
            if(next!=null&&!start.isBefore(next))forceRefresh=false;
        }
        boolean scoped=Boolean.TRUE.equals(job.get("scoped_demand"));
        InvestmentHistoryPreparationService.PreparationResult result =
                !scoped ? historyPreparation.prepareProduct(product, jobTypeFor(product), forceRefresh, start, end)
                        : historyPreparation.prepareDemandProduct(product,jobTypeFor(product),forceRefresh,start,end);
        boolean completed = !end.isBefore(target);
        boolean emptyHistory = completed && "BACKFILL".equals(value(job, "job_type")) && result.recordCount() == 0;
        boolean missingSeries = completed && "BACKFILL".equals(value(job, "job_type"))
                && historyPreparation.incrementalStart(product) == null;
        boolean closedTail=!scoped && historyPreparation.closedAfter(product,result.sampleEndDate(),end);
        LocalDate verified=scoped ? result.verifiedThrough() : null;
        boolean resumeFull=Boolean.TRUE.equals(job.get("resume_full"));
        LocalDateTime retry=resumeFull&&(verified==null||verified.isBefore(end))?LocalDateTime.now(CN_ZONE).plusHours(Math.max(1,failedRetryHours)):null;
        String next=emptyHistory||missingSeries?"FAILED":resumeFull?(retry==null?"QUEUED":"RETRY_WAIT"):completed||(scoped&&verified==null)?"SUCCEEDED":"QUEUED";
        completeWindow(jobId, token, next, resumeFull?day(job.get("full_checkpoint")):end,
                emptyHistory ? "NO_HISTORY_DATA" : missingSeries ? REQUIRED_SERIES_MISSING
                        : scoped && verified==null ? "UNVERIFIED_WINDOW"
                        : !closedTail && (result.skipped() || (result.sampleEndDate()!=null && result.sampleEndDate().isBefore(end))) ? "NO_NEW_DATA" : null, product.getId(), start,
                verified,
                !result.skipped() || ANALYSIS_QUEUE_FAILED.equals(value(job, "last_error")),retry);
    }

    private void completeWindow(long jobId, String token, String status, LocalDate checkpoint,
                                String note, Long productId, LocalDate start, LocalDate preparedThrough, boolean queueAnalysis,LocalDateTime retryAt) {
        Supplier<Integer> action = () -> {
            int owned = finish(jobId, token, status, checkpoint, note);
            if(owned==1&&retryAt!=null)db.update("UPDATE market_data_job SET next_retry_at=? WHERE id=? AND status='RETRY_WAIT'",retryAt,jobId);
            if (owned == 1 && requirements != null && !"FAILED".equals(status) && preparedThrough != null)
                requirements.recordPrepared(productId, "PRICE", start, preparedThrough);
            if (owned == 1 && queueAnalysis && tasks != null) {
                try {
                    tasks.queueAnalysisForProduct(productId);
                } catch (RuntimeException failure) {
                    throw new AnalysisQueueException(failure);
                }
            }
            return owned;
        };
        if (claimTransaction == null) action.get();
        else claimTransaction.execute(transaction -> action.get());
    }

    private static final class AnalysisQueueException extends RuntimeException {
        private AnalysisQueueException(RuntimeException cause) { super(ANALYSIS_QUEUE_FAILED, cause); }
    }

    private static String jobTypeFor(InvestmentProduct product) {
        String type = product.getProductType();
        if ("MUTUAL_FUND".equalsIgnoreCase(type) || "FUND".equalsIgnoreCase(type)) {
            return "FUND_NAV_HISTORY";
        }
        return "STOCK_HISTORY";
    }

    private int finish(long jobId, String token, String status, LocalDate checkpoint, String note) {
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        return db.update("UPDATE market_data_job SET status=?,checkpoint_date=?,lease_until=NULL,lease_token=NULL,"
                        + "next_retry_at=NULL,attempt_count=0,last_error=?,updated_at=? "
                        + "WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?",
                status, checkpoint, note, now, jobId, token, now);
    }

    private void setCatalogState(String market, String status, int count, String error) {
        LocalDateTime now = LocalDateTime.now(CN_ZONE);
        int updated = db.update("UPDATE market_catalog_state SET status=?,product_count=?,last_synced_at=?,"
                        + "last_error=? WHERE market=?", status, count, now,
                error == null ? null : error.substring(0, Math.min(500, error.length())), market);
        if (updated == 0) {
            try {
                db.update("INSERT INTO market_catalog_state(market,status,product_count,last_synced_at,last_error) "
                        + "VALUES(?,?,?,?,?)", market, status, count, now, trim(error));
            } catch (DataAccessException concurrentImport) {
                if (db.queryForObject("SELECT COUNT(*) FROM market_catalog_state WHERE market=?",
                        Integer.class, market) == 0) throw concurrentImport;
                db.update("UPDATE market_catalog_state SET status=?,product_count=?,last_synced_at=?,last_error=? WHERE market=?",
                        status, count, now, trim(error), market);
            }
        }
    }

    private static String trim(String error) {
        return error == null ? null : error.substring(0, Math.min(500, error.length()));
    }

    private static InvestmentProduct candidateProduct(Map<String, Object> row) {
        InvestmentProduct product = new InvestmentProduct();
        product.setId(((Number) row.get("id")).longValue());
        product.setProductType(value(row, "product_type"));
        product.setMarket(value(row, "market"));
        product.setFundCategory(value(row,"fund_category"));
        product.setDelistingDate(day(row.get("delisting_date")));
        product.setCode(value(row, "code"));
        product.setListingDate(day(row.get("listing_date")));
        product.setInceptionDate(day(row.get("inception_date")));
        product.setHistoryEndDate(day(row.get("history_end_date")));
        return product;
    }

    private static String key(String type, String market, String code) {
        return type + ":" + market + ":" + code;
    }

    private static String value(Map<String, Object> row, String key) {
        Object item = row.get(key);
        return item == null || item.toString().isBlank() ? null : item.toString();
    }

    private static LocalDate day(Object value) {
        return value == null ? null : value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }

    private static LocalDateTime timestamp(Object value) {
        if (value instanceof java.sql.Timestamp time) return time.toLocalDateTime();
        if (value instanceof LocalDateTime time) return time;
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
