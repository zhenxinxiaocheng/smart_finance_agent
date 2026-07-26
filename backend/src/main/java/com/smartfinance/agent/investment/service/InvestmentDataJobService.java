package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.mapper.InvestmentDataJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InvestmentDataJobService {
    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final InvestmentDataJobMapper mapper;

    public InvestmentDataJobService(InvestmentDataJobMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public InvestmentDataJob ensureQueued(Long userId, Long assetId, Long productId,
                                           String productType, boolean forceRefresh) {
        String jobType = jobTypeFor(productType);
        InvestmentDataJob existing = findByAssetAndType(assetId, jobType);
        if (existing != null) {
            if (forceRefresh) {
                resetForRefresh(existing);
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
        job.setForceRefresh(forceRefresh);
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
        InvestmentDataJob job = mapper.selectOne(new LambdaQueryWrapper<InvestmentDataJob>()
                .eq(InvestmentDataJob::getUserId, userId)
                .eq(InvestmentDataJob::getAssetId, assetId)
                .orderByDesc(InvestmentDataJob::getUpdatedAt)
                .last("LIMIT 1"));
        if (job == null) {
            return Map.of();
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", job.getStatus());
        status.put("recordCount", job.getRecordCount());
        status.put("attemptCount", job.getAttemptCount());
        status.put("errorMessage", job.getErrorMessage());
        status.put("updatedAt", job.getUpdatedAt());
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

    private InvestmentDataJob findByAssetAndType(Long assetId, String jobType) {
        return mapper.selectOne(new LambdaQueryWrapper<InvestmentDataJob>()
                .eq(InvestmentDataJob::getAssetId, assetId)
                .eq(InvestmentDataJob::getJobType, jobType));
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
