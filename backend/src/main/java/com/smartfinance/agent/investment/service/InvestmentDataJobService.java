package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.entity.InvestmentAsset;
import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.mapper.InvestmentAssetMapper;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import com.smartfinance.agent.investment.mapper.InvestmentProductMapper;
import com.smartfinance.agent.investment.mapper.ProductDailyQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InvestmentDataJobService {
    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final InvestmentDataJobMapper mapper;
    private final InvestmentProductMapper productMapper;
    private final InvestmentAssetMapper assetMapper;
    private final ProductDailyQuoteMapper quoteMapper;

    public InvestmentDataJobService(InvestmentDataJobMapper mapper,
                                    InvestmentProductMapper productMapper,
                                    InvestmentAssetMapper assetMapper,
                                    ProductDailyQuoteMapper quoteMapper) {
        this.mapper = mapper;
        this.productMapper = productMapper;
        this.assetMapper = assetMapper;
        this.quoteMapper = quoteMapper;
    }

    @Transactional
    public InvestmentDataJob ensureQueued(Long userId, Long assetId, Long productId,
                                           String productType, boolean forceRefresh) {
        String jobType = jobTypeFor(productType);
        boolean requiresCompleteHistory = isAssetHistoryJob(jobType)
                && !Boolean.TRUE.equals(historyCoverageComplete(productId));
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
        InvestmentDataJob existing = findByAssetAndType(assetId, jobType);
        if (existing == null) {
            return ensureQueued(userId, assetId, productId, productType, false);
        }
        if (isActive(existing)) {
            return existing;
        }
        LocalDate latestQuoteDate = "FUND_NAV_HISTORY".equals(jobType)
                ? quoteMapper.latestCompleteFundTradeDate(productId)
                : quoteMapper.latestTradeDate(productId);
        if ("PARTIAL".equals(existing.getStatus()) && latestQuoteDate != null
                && existing.getSampleEndDate() != null
                && latestQuoteDate.isAfter(existing.getSampleEndDate())) {
            return requeueTerminal(existing, true);
        }
        if (analysisStale && "FUND_NAV_HISTORY".equals(jobType)
                && "PARTIAL".equals(existing.getStatus())
                && existing.getSampleEndDate() != null
                && quoteMapper.hasMissingFundReturns(productId)
                && !quoteMapper.hasMissingFundReturns(productId, existing.getSampleEndDate())) {
            // One full retry repairs jobs that counted a newer display-only NAV as incomplete history.
            if (mapper.requeuePartialRecoveryOnce(existing.getId()) == 1) {
                return findByAssetAndType(assetId, jobType);
            }
        }
        if (!"SUCCEEDED".equals(existing.getStatus())) {
            return existing;
        }
        if (!analysisStale && (latestQuoteDate == null || (existing.getSampleEndDate() != null
                && !latestQuoteDate.isAfter(existing.getSampleEndDate())))) {
            return existing;
        }
        return requeueTerminal(existing, false);
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
    public int requeueIncompleteHistoryJobs() {
        int requeued = 0;
        for (InvestmentAsset asset : assetMapper.selectList(null)) {
            InvestmentProduct product = productMapper.selectById(asset.getProductId());
            if (product == null || !isAssetHistoryProduct(product)) {
                continue;
            }
            String jobType = jobTypeFor(product.getProductType());
            InvestmentDataJob existing = findByAssetAndType(asset.getId(), jobType);
            if (existing != null || Boolean.TRUE.equals(historyCoverageComplete(product.getId()))) {
                continue;
            }
            ensureQueued(
                    asset.getUserId(),
                    asset.getId(),
                    product.getId(),
                    product.getProductType(),
                    false
            );
            requeued++;
        }
        return requeued;
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

    public Map<String, Object> statusForAsset(Long userId, Long assetId) {
        InvestmentAsset asset = assetMapper.selectOne(new LambdaQueryWrapper<InvestmentAsset>()
                .eq(InvestmentAsset::getUserId, userId)
                .eq(InvestmentAsset::getId, assetId)
                .eq(InvestmentAsset::getDeleted, 0));
        if (asset == null) {
            return Map.of();
        }
        InvestmentProduct product = productMapper.selectById(asset.getProductId());
        if (product == null) {
            return Map.of();
        }
        InvestmentDataJob job = findByAssetAndType(
                assetId,
                jobTypeFor(product.getProductType())
        );
        if (job == null) {
            return Map.of();
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", job.getStatus());
        status.put("recordCount", job.getRecordCount());
        status.put("attemptCount", job.getAttemptCount());
        status.put("errorMessage", job.getErrorMessage());
        status.put("updatedAt", job.getUpdatedAt());
        status.put("requestedStartDate", job.getRequestedStartDate());
        status.put("sampleStartDate", job.getSampleStartDate());
        status.put("sampleEndDate", job.getSampleEndDate());
        status.put("coverageComplete", job.getCoverageComplete());
        status.put("datasetVersion", job.getDatasetVersion());
        return status;
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

    public boolean recordCoverage(
            Long id,
            String leaseToken,
            InvestmentHistoryPreparationService.PreparationResult result) {
        if (result == null) {
            return false;
        }
        return mapper.updateCoverage(
                id,
                leaseToken,
                result.requestedStartDate(),
                result.sampleStartDate(),
                result.sampleEndDate(),
                result.coverageComplete(),
                result.datasetVersion()) == 1;
    }

    private InvestmentDataJob findByAssetAndType(Long assetId, String jobType) {
        return mapper.selectOne(new LambdaQueryWrapper<InvestmentDataJob>()
                .eq(InvestmentDataJob::getAssetId, assetId)
                .eq(InvestmentDataJob::getJobType, jobType));
    }

    private Boolean historyCoverageComplete(Long productId) {
        InvestmentProduct product = productMapper.selectById(productId);
        if (product == null || !Boolean.TRUE.equals(product.getHistoryCoverageComplete())) {
            return false;
        }
        return !"FUND_NAV_HISTORY".equals(jobTypeFor(product.getProductType()))
                || !quoteMapper.hasMissingFundReturns(productId, product.getHistoryEndDate());
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
