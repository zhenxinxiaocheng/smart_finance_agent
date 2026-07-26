package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentHorizonProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.entity.InvestmentDataJob;
import com.smartfinance.agent.investment.quant.QuantAutomationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final QuantAutomationService quantAutomationService;

    @Autowired
    public InvestmentDataJobWorker(InvestmentDataJobService jobService,
                                   InvestmentAnalysisService analysisService,
                                   InvestmentHorizonProperties horizonProperties,
                                   QuantAutomationService quantAutomationService) {
        this(
                jobService,
                analysisService,
                horizonProperties,
                Clock.system(RUNTIME_ZONE),
                quantAutomationService
        );
    }

    InvestmentDataJobWorker(InvestmentDataJobService jobService,
                            InvestmentAnalysisService analysisService,
                            InvestmentHorizonProperties horizonProperties,
                            Clock clock,
                            QuantAutomationService quantAutomationService) {
        this.jobService = jobService;
        this.analysisService = analysisService;
        this.horizonProperties = horizonProperties;
        this.clock = clock;
        this.quantAutomationService = quantAutomationService;
    }

    @Scheduled(fixedDelayString = "${investment.history-job.scan-delay-ms:1000}")
    public void scan() {
        for (InvestmentDataJob job : jobService.pendingJobs(BATCH_LIMIT)) {
            run(job);
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
            InvestmentAssetDetailResponse detail = Boolean.TRUE.equals(claimedJob.getForceRefresh())
                    ? analysisService.retryData(claimedJob.getUserId(), claimedJob.getAssetId())
                    : analysisService.refresh(claimedJob.getUserId(), claimedJob.getAssetId());
            int recordCount = detail == null || detail.getQuoteSeries() == null
                    ? 0 : detail.getQuoteSeries().size();
            int minimum = horizonProperties.getMinimumHistoryTradingDays();
            LocalDateTime completionTime = LocalDateTime.now(clock);
            if (recordCount >= minimum) {
                jobService.markSucceeded(claimedJob.getId(), leaseToken, recordCount, completionTime);
                triggerQuantAutomation(claimedJob);
            } else if (recordCount > 0) {
                jobService.markPartial(claimedJob.getId(), leaseToken, recordCount,
                        truncate("历史记录仅 " + recordCount + " 条，低于最低要求 " + minimum + " 条"),
                        completionTime);
            } else {
                retryOrFail(claimedJob, leaseToken, completionTime, "没有历史数据");
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            retryOrFail(claimedJob, leaseToken, LocalDateTime.now(clock),
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
        }
    }

    private void triggerQuantAutomation(InvestmentDataJob job) {
        try {
            quantAutomationService.onDataReady(job.getUserId(), job.getAssetId());
        } catch (RuntimeException exception) {
            log.warn(
                    "Quant automation deferred after data refresh: assetId={}",
                    job.getAssetId(),
                    exception
            );
        }
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
