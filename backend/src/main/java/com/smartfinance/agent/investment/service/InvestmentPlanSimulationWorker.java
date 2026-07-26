package com.smartfinance.agent.investment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
public class InvestmentPlanSimulationWorker {

    private final InvestmentPlanSimulationService simulationService;
    private final ZoneId zone;
    private final int batchLimit;

    public InvestmentPlanSimulationWorker(InvestmentPlanSimulationService simulationService,
                                          @Value("${investment.plan.zone:Asia/Shanghai}") String zone,
                                          @Value("${investment.plan.batch-limit:20}") int batchLimit) {
        this.simulationService = simulationService;
        this.zone = ZoneId.of(zone);
        this.batchLimit = Math.max(1, batchLimit);
    }

    @Scheduled(initialDelayString = "${investment.plan.initial-delay-ms:10000}",
            fixedDelayString = "${investment.plan.scan-delay-ms:60000}")
    public void run() {
        LocalDate today = LocalDate.now(zone);
        for (Long planId : simulationService.duePlanIds(today, batchLimit)) {
            try {
                simulationService.execute(planId, today);
            } catch (RuntimeException exception) {
                log.warn("Recurring investment simulation failed for plan {}: {}", planId, exception.getMessage());
                simulationService.markFailed(planId, exception.getMessage());
            }
        }
    }
}
