package com.smartfinance.agent.investment.quant.workbench;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class WorkbenchScheduling {
    @Bean(name="taskScheduler")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="taskScheduler")
    public ThreadPoolTaskScheduler defaultScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduled-task-");
        return scheduler;
    }
    @Bean(name="quantWorkbenchScheduler")
    public ThreadPoolTaskScheduler quantWorkbenchScheduler() {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("quant-workbench-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
