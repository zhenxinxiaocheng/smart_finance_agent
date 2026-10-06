package com.smartfinance.agent.investment.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 市场调度独立于个人资产；执行池限流且不在内存堆积任务。 */
@Configuration
public class MarketDataScheduling {
    @Bean(name = "marketDataExecutor")
    public ThreadPoolTaskExecutor marketExecutor(@Value("${market-data.worker.concurrency:2}") int concurrency) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("市场任务并发配置无效");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("market-data-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
    @Bean(name = "marketCatalogScheduler")
    public ThreadPoolTaskScheduler catalogScheduler() {
        return scheduler("market-catalog-");
    }

    @Bean(name = "marketHistoryScheduler")
    public ThreadPoolTaskScheduler historyScheduler() {
        return scheduler("market-history-");
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
