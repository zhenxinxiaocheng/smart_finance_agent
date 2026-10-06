package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.entity.ProductDailyQuote;
import com.smartfinance.agent.investment.domain.GlobalHorizonChangedEvent;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class InvestmentDataJobService {
    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final InvestmentDataJobMapper mapper;
    private final InvestmentProductMapper productMapper;
    private final ProductDailyQuoteMapper quoteMapper;
    private final InvestmentAssetMapper assetMapper;
    private InvestmentDetailCacheService detailCache;
    private MarketDataDemandService demand;
    @Autowired
    void configureDemand(MarketDataDemandService demand) { this.demand = demand; }

    @Autowired
    void configureDetailCache(InvestmentDetailCacheService detailCache) {
        this.detailCache = detailCache;
    }

    public InvestmentDataJobService(InvestmentDataJobMapper mapper,
                                    InvestmentProductMapper productMapper,
                                    ProductDailyQuoteMapper quoteMapper) {
        this(mapper, productMapper, quoteMapper, null);
    }

    @Autowired
    public InvestmentDataJobService(InvestmentDataJobMapper mapper,
                                    InvestmentProductMapper productMapper,
                                    ProductDailyQuoteMapper quoteMapper,
                                    InvestmentAssetMapper assetMapper) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.quoteMapper = quoteMapper;
        this.assetMapper = assetMapper;
    }

    @Transactional
    public InvestmentDataJob ensureQueued(Long userId, Long assetId, Long productId,
                                           String productType, boolean forceRefresh) {
        String jobType = jobTypeFor(productType);
        if (demand != null && isAssetHistoryJob(jobType)) demand.asset(userId,assetId,productId);
        // 按需模式的首次准备交给共享采集任务，forceRefresh 仅保留用户显式修复语义。
        boolean requiresCompleteHistory = demand == null && isAssetHistoryJob(jobType) && !hasLocalHistory(productId);
        InvestmentDataJob existing = findByAssetAndType(assetId, jobType);
        if (existing != null) {
            if (isActive(existing)) {
                return existing;
            }
            if (forceRefresh) {
                return requeueTerminal(existing, true);
            }
            return existing;
        }

        InvestmentDataJob job = new InvestmentDataJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setProductId(productId);
        job.setJobType(jobType);
        job.setStatus("QUEUED");
        job.setForceRefresh(forceRefresh || requiresCompleteHistory);
        job.setRecordCount(0);
        job.setAttemptCount(0);
        try {
            mapper.insert(job);
        } catch (DuplicateKeyException duplicateKey) {
            InvestmentDataJob winningJob = findByAssetAndType(assetId, jobType);
            if (winningJob != null) {
                return winningJob;
            }
            throw duplicateKey;
        }
        return job;
    }

    @Transactional
    public InvestmentDataJob ensureRecoveryQueued(Long userId, Long assetId, Long productId,
                                                   String productType) {
        return ensureRecoveryQueued(userId, assetId, productId, productType, false);
    }

    @Transactional
    public InvestmentDataJob ensureRecoveryQueued(Long userId, Long assetId, Long productId,
                                                   String productType, boolean analysisStale) {
        String jobType = jobTypeFor(productType);
        if (demand != null && isAssetHistoryJob(jobType)) demand.asset(userId,assetId,productId);
        InvestmentDataJob existing = findByAssetAndType(assetId, jobType);
        if (existing == null) {
            return ensureQueued(userId, assetId, productId, productType, false);
        }
        if (isActive(existing)) {
            return existing;
        }
        // 已有终态任务且分析过期时只做增量补齐，绝不因 PARTIAL / FAILED 触发全量回补。
        if (analysisStale && List.of("SUCCEEDED", "PARTIAL", "FAILED").contains(existing.getStatus())) {
            return requeueTerminal(existing, false);
        }
        return existing;
    }

    /** 新行情落库后，只为仍存在的个人资产排队重建分析。 */
    @Transactional
    public void queueAnalysisForProduct(Long productId) {
        if (productId == null || assetMapper == null) {
            return;
        }
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null || !isAssetHistoryProduct(product)) {
            return;
        }
        List<InvestmentAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                .select(InvestmentAsset::getId, InvestmentAsset::getUserId, InvestmentAsset::getProductId)
                .eq(InvestmentAsset::getProductId, productId)
                .eq(InvestmentAsset::getDeleted, 0));
        for (InvestmentAsset asset : assets) {
            if (asset.getId() != null && asset.getUserId() != null) {
                ensureRecoveryQueued(asset.getUserId(), asset.getId(), productId,
                        product.getProductType(), true);
                if (detailCache != null) detailCache.evict(asset.getUserId(), asset.getId());
            }
        }
    }

    /** 周期保存和分析入队使用同一事务；仅重算当前用户仍存在的资产。 */
    @EventListener
    @Transactional
    public void onGlobalHorizonChanged(GlobalHorizonChangedEvent event) {
        if (assetMapper == null) return;
        List<InvestmentAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                .select(InvestmentAsset::getId, InvestmentAsset::getUserId, InvestmentAsset::getProductId)
                .eq(InvestmentAsset::getUserId, event.userId())
                .eq(InvestmentAsset::getDeleted, 0));
        for (InvestmentAsset asset : assets) {
            InvestmentProduct product = asset.getProductId() == null ? null : productMapper.selectById(asset.getProductId());
            if (product != null && isAssetHistoryProduct(product)) {
                ensureRecoveryQueued(asset.getUserId(), asset.getId(), product.getId(), product.getProductType(), true);
                if (detailCache != null) detailCache.evict(asset.getUserId(), asset.getId());
            }
        }
    }

    /** 启动后核对既有持仓，补齐停机期间遗漏的数据或分析更新。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resumeHeldData() {
        if (assetMapper == null) return;
        assetMapper.selectList(new LambdaQueryWrapper<InvestmentAsset>()
                        .select(InvestmentAsset::getProductId).eq(InvestmentAsset::getDeleted, 0))
                .stream().map(InvestmentAsset::getProductId).filter(java.util.Objects::nonNull)
                .distinct().forEach(this::queueAnalysisForProduct);
    }

    private InvestmentDataJob requeueTerminal(InvestmentDataJob existing, boolean forceRefresh) {
        // Another request may already have requeued/claimed this row since our SELECT.
        int updated = forceRefresh
                ? mapper.requeueTerminal(existing.getId())
                : mapper.requeueTerminalIncremental(existing.getId());
        if (updated == 1) {
            resetForRefresh(existing);
            existing.setForceRefresh(forceRefresh);
            return existing;
        }
        return findByAssetAndType(existing.getAssetId(), existing.getJobType());
    }

    @Transactional
    public InvestmentDataJob ensureBenchmarkQueued(Long userId, Long assetId, Long productId) {
        return ensureSpecialQueued(userId, assetId, productId, "BENCHMARK_HISTORY");
    }

    private InvestmentDataJob ensureSpecialQueued(Long userId,
                                                  Long assetId,
                                                  Long productId,
                                                  String jobType) {
        InvestmentDataJob existing = findByAssetAndType(assetId, jobType);
        if (existing != null) {
            boolean retryBenchmarkNow = "BENCHMARK_HISTORY".equals(jobType)
                    && ("RETRY_WAIT".equals(existing.getStatus())
                    || ("QUEUED".equals(existing.getStatus())
                    && (existing.getNextRetryAt() != null
                    || existing.getErrorMessage() != null)));
            if (retryBenchmarkNow || List.of("SUCCEEDED", "FAILED", "PARTIAL", "CANCELLED")
                    .contains(existing.getStatus())) {
                resetForRefresh(existing);
                existing.setForceRefresh(false);
                mapper.updateById(existing);
            }
            return existing;
        }

        InvestmentDataJob job = new InvestmentDataJob();
        job.setUserId(userId);
        job.setAssetId(assetId);
        job.setProductId(productId);
        job.setJobType(jobType);
        job.setStatus("QUEUED");
        job.setForceRefresh(false);
        job.setRecordCount(0);
        job.setAttemptCount(0);
        try {
            mapper.insert(job);
        } catch (DuplicateKeyException duplicateKey) {
            InvestmentDataJob winningJob = findByAssetAndType(assetId, jobType);
            if (winningJob != null) {
                return winningJob;
            }
            throw duplicateKey;
        }
        return job;
    }

    public List<InvestmentDataJob> pendingJobs(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now(RUNTIME_ZONE);
        return mapper.selectList(new LambdaQueryWrapper<InvestmentDataJob>()
                .and(wrapper -> wrapper
                        .nested(active -> active.in(InvestmentDataJob::getStatus, "QUEUED", "RETRY_WAIT")
                                .and(retry -> retry.isNull(InvestmentDataJob::getNextRetryAt)
                                        .or()
                                        .le(InvestmentDataJob::getNextRetryAt, now)))
                        .or()
                        .eq(InvestmentDataJob::getStatus, "RUNNING")
                        .isNotNull(InvestmentDataJob::getLeaseUntil)
                        .le(InvestmentDataJob::getLeaseUntil, now))
                .orderByAsc(InvestmentDataJob::getCreatedAt)
                .last("LIMIT " + boundedLimit));
    }

    public boolean claim(Long id, LocalDateTime now, LocalDateTime leaseUntil, String leaseToken) {
        return mapper.claim(id, now, leaseUntil, leaseToken) == 1;
    }

    public InvestmentDataJob claimedSnapshot(Long id, String leaseToken) {
        if (id == null || leaseToken == null || leaseToken.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<InvestmentDataJob>()
                .eq(InvestmentDataJob::getId, id)
                .eq(InvestmentDataJob::getLeaseToken, leaseToken)
                .eq(InvestmentDataJob::getStatus, "RUNNING")
                .last("LIMIT 1"));
    }

    public boolean markSucceeded(Long id, String leaseToken, int recordCount, LocalDateTime finishedAt) {
        return mapper.finish(id, leaseToken, "SUCCEEDED", recordCount, null, finishedAt) == 1;
    }

    public boolean markPartial(Long id, String leaseToken, int recordCount, String errorMessage,
                               LocalDateTime finishedAt) {
        return mapper.finish(id, leaseToken, "PARTIAL", recordCount, errorMessage, finishedAt) == 1;
    }

    public boolean markRetryWait(Long id, String leaseToken, int attemptCount,
                                 LocalDateTime nextRetryAt, String errorMessage, LocalDateTime updatedAt) {
        return mapper.reschedule(id, leaseToken, "RETRY_WAIT", attemptCount, nextRetryAt,
                errorMessage, null, updatedAt) == 1;
    }

    public boolean markFailed(Long id, String leaseToken, int attemptCount, String errorMessage,
                              LocalDateTime finishedAt) {
        return mapper.reschedule(id, leaseToken, "FAILED", attemptCount, null,
                errorMessage, finishedAt, finishedAt) == 1;
    }

    private InvestmentDataJob findByAssetAndType(Long assetId, String jobType) {
        return mapper.selectOne(new LambdaQueryWrapper<InvestmentDataJob>()
                .eq(InvestmentDataJob::getAssetId, assetId)
                .eq(InvestmentDataJob::getJobType, jobType));
    }

    /** 本地真实行情决定是否首次回补，不依赖覆盖标记或上一次任务状态。 */
    private boolean hasLocalHistory(Long productId) {
        return quoteMapper.selectCount(new LambdaQueryWrapper<ProductDailyQuote>()
                .eq(ProductDailyQuote::getProductId, productId)) > 0;
    }

    private static boolean isAssetHistoryJob(String jobType) {
        return "STOCK_HISTORY".equals(jobType) || "FUND_NAV_HISTORY".equals(jobType);
    }

    private static boolean isActive(InvestmentDataJob job) {
        return job != null && List.of("QUEUED", "RUNNING", "RETRY_WAIT")
                .contains(job.getStatus());
    }

    private static boolean isAssetHistoryProduct(InvestmentProduct product) {
        String productType = product.getProductType();
        return "STOCK".equalsIgnoreCase(productType)
                || "FUND".equalsIgnoreCase(productType)
                || "MUTUAL_FUND".equalsIgnoreCase(productType);
    }

    private static void resetForRefresh(InvestmentDataJob job) {
        job.setStatus("QUEUED");
        job.setForceRefresh(true);
        job.setRecordCount(0);
        job.setAttemptCount(0);
        job.setNextRetryAt(null);
        job.setLeaseUntil(null);
        job.setLeaseToken(null);
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
    }

    private static String jobTypeFor(String productType) {
        if ("FUND".equalsIgnoreCase(productType) || "MUTUAL_FUND".equalsIgnoreCase(productType)) {
            return "FUND_NAV_HISTORY";
        }
        if ("STOCK".equalsIgnoreCase(productType)) {
            return "STOCK_HISTORY";
        }
        throw new IllegalArgumentException("不支持的产品类型: " + productType);
    }
}
