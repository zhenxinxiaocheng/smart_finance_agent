package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.quant.QuantBenchmarkPreparationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class InvestmentDataJobWorker {

    private static final ZoneId RUNTIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int BATCH_LIMIT = 2;
    private static final int LEASE_SECONDS = 120;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final InvestmentDataJobService jobService;
    private final InvestmentAnalysisService analysisService;
    private final InvestmentHorizonProperties horizonProperties;
    private final Clock clock;
    private final QuantBenchmarkPreparationService benchmarkPreparationService;
    private final InvestmentHistoryPreparationService historyPreparationService;

    @Autowired
    public InvestmentDataJobWorker(InvestmentDataJobService jobService,
                                   InvestmentAnalysisService analysisService,
                                   InvestmentHorizonProperties horizonProperties,
                                   QuantBenchmarkPreparationService benchmarkPreparationService,
                                   InvestmentHistoryPreparationService historyPreparationService) {
        this(
                jobService,
                analysisService,
                horizonProperties,
                Clock.system(RUNTIME_ZONE),
                benchmarkPreparationService,
                historyPreparationService
        );
    }

    InvestmentDataJobWorker(InvestmentDataJobService jobService,
                            InvestmentAnalysisService analysisService,
                            InvestmentHorizonProperties horizonProperties,
                            Clock clock,
                            QuantBenchmarkPreparationService benchmarkPreparationService,
                            InvestmentHistoryPreparationService historyPreparationService) {
        this.jobService = jobService;
        this.analysisService = analysisService;
        this.horizonProperties = horizonProperties;
        this.clock = clock;
        this.benchmarkPreparationService = benchmarkPreparationService;
        this.historyPreparationService = historyPreparationService;
    }

    @Scheduled(fixedDelayString = "${investment.history-job.scan-delay-ms:1000}")
    public void scan() {
        for (InvestmentDataJob job : jobService.pendingJobs(BATCH_LIMIT)) {
            run(job);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${investment.history-job.recovery-scan-delay-ms:60000}")
    public void requeueIncompleteHistoryJobs() {
        try {
            int requeued = jobService.requeueIncompleteHistoryJobs();
            if (requeued > 0) {
                log.info("Requeued {} incomplete asset history jobs for full backfill", requeued);
            }
        } catch (RuntimeException exception) {
            log.warn("Incomplete asset history backfill scan failed: {}", exception.getMessage());
        }
    }

    private void run(InvestmentDataJob job) {
        LocalDateTime claimTime = LocalDateTime.now(clock);
        String leaseToken = UUID.randomUUID().toString();
        if (!jobService.claim(job.getId(), claimTime, claimTime.plusSeconds(LEASE_SECONDS), leaseToken)) {
            return;
        }
        InvestmentDataJob claimedJob = jobService.claimedSnapshot(job.getId(), leaseToken);
        if (claimedJob == null) {
            return;
        }
        try {
            int recordCount;
            InvestmentHistoryPreparationService.PreparationResult historyResult = null;
            if ("BENCHMARK_HISTORY".equals(claimedJob.getJobType())) {
                recordCount = benchmarkPreparationService.prepare(claimedJob);
            } else if (isAssetHistoryJob(claimedJob)) {
                historyResult = historyPreparationService.prepare(claimedJob);
                recordCount = historyResult.recordCount();
            } else {
                InvestmentAssetDetailResponse detail = Boolean.TRUE.equals(claimedJob.getForceRefresh())
                        ? analysisService.retryData(claimedJob.getUserId(), claimedJob.getAssetId())
                        : analysisService.refresh(claimedJob.getUserId(), claimedJob.getAssetId());
                recordCount = detail == null || detail.getQuoteSeries() == null
                        ? 0 : detail.getQuoteSeries().size();
            }
            int minimum = horizonProperties.getMinimumHistoryTradingDays();
            LocalDateTime completionTime = LocalDateTime.now(clock);
            if (historyResult != null
                    && !jobService.recordCoverage(claimedJob.getId(), leaseToken, historyResult)) {
                return;
            }
            if (historyResult != null && !historyResult.coverageComplete()) {
                jobService.markPartial(
                        claimedJob.getId(),
                        leaseToken,
                        recordCount,
                        "历史覆盖不完整，已阻止正式训练",
                        completionTime);
                return;
            }
            if (recordCount >= minimum) {
                if (historyResult != null) {
                    requireFreshAnalysis(claimedJob);
                    completionTime = LocalDateTime.now(clock);
                }
                jobService.markSucceeded(
                        claimedJob.getId(),
                        leaseToken,
                        recordCount,
                        completionTime
                );
            } else if (recordCount > 0) {
                jobService.markPartial(claimedJob.getId(), leaseToken, recordCount,
                        truncate("历史记录仅 " + recordCount + " 条，低于最低要求 " + minimum + " 条"),
                        completionTime);
            } else {
                retryOrFail(claimedJob, leaseToken, completionTime, "没有历史数据");
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            String error = message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message;
            LocalDateTime failureTime = LocalDateTime.now(clock);
            if (exception instanceof InvestmentHistoryPreparationService.QualityBlockedException) {
                int attempts = (claimedJob.getAttemptCount() == null ? 0 : claimedJob.getAttemptCount()) + 1;
                jobService.markFailed(claimedJob.getId(), leaseToken, attempts, truncate(error), failureTime);
            } else {
                retryOrFail(claimedJob, leaseToken, failureTime, error);
            }
        }
    }

    private void requireFreshAnalysis(InvestmentDataJob job) {
        InvestmentAssetDetailResponse detail = analysisService.refresh(job.getUserId(), job.getAssetId());
        Map<String, Object> sourceStatus = detail == null ? null : detail.getSourceStatus();
        boolean ready = sourceStatus != null
                && "READY".equals(sourceStatus.get("dataState"))
                && !Boolean.TRUE.equals(sourceStatus.get("historicalCache"));
        if (!ready) {
            throw new IllegalStateException("历史数据已更新，但尚未生成新分析结果");
        }
    }

    private static boolean isAssetHistoryJob(InvestmentDataJob job) {
        return "STOCK_HISTORY".equals(job.getJobType())
                || "FUND_NAV_HISTORY".equals(job.getJobType());
    }

    private void retryOrFail(InvestmentDataJob job, String leaseToken,
                             LocalDateTime now, String errorMessage) {
        int attempts = (job.getAttemptCount() == null ? 0 : job.getAttemptCount()) + 1;
        String safeError = truncate(errorMessage);
        if (attempts >= MAX_ATTEMPTS) {
            jobService.markFailed(job.getId(), leaseToken, attempts, safeError, now);
            return;
        }
        long delaySeconds = attempts == 1 ? 60 : 300;
        jobService.markRetryWait(job.getId(), leaseToken, attempts,
                now.plusSeconds(delaySeconds), safeError, now);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
