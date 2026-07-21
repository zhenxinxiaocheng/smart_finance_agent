package com.smartfinance.agent.investment.quant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QuantJobWorker {
    private static final Logger log = LoggerFactory.getLogger(QuantJobWorker.class);

    private final QuantJobMapper jobMapper;
    private final QuantService quantService;
    private final int batchLimit;

    public QuantJobWorker(QuantJobMapper jobMapper,
                          QuantService quantService,
                          @Value("${investment.quant.job-batch-limit}") int batchLimit) {
        this.jobMapper = jobMapper;
        this.quantService = quantService;
        this.batchLimit = batchLimit;
    }

    @Scheduled(
            initialDelayString = "${investment.quant.initial-delay-ms}",
            fixedDelayString = "${investment.quant.poll-delay-ms}"
    )
    public void persistCompletedJobs() {
        List<QuantJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<QuantJob>()
                .in(QuantJob::getStatus, "QUEUED", "RUNNING")
                .orderByAsc(QuantJob::getCreatedAt)
                .last("LIMIT " + batchLimit));
        for (QuantJob job : jobs) {
            try {
                quantService.job(job.getUserId(), job.getExternalJobId());
            } catch (RuntimeException exception) {
                log.warn("Quant job poll deferred: jobId={}", job.getExternalJobId());
            }
        }
    }
}
