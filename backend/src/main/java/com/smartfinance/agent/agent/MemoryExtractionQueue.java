package com.smartfinance.agent.agent;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps optional memory work off the response path and preserves submission order. */
@Component
public class MemoryExtractionQueue {
    private final ThreadPoolExecutor executor;

    public MemoryExtractionQueue(@Value("${agent.memory.queue-capacity:64}") int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("agent.memory.queue-capacity must be positive");
        }
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "agent-memory");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // Optional work must never fall back to running on the request thread.
            return false;
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
