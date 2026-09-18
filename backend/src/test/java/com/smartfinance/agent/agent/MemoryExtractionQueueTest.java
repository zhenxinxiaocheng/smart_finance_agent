package com.smartfinance.agent.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractionQueueTest {
    @Test
    void slowExtractionAndFullQueueMustNotBlockTheCaller() throws Exception {
        MemoryExtractionQueue queue = new MemoryExtractionQueue(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        try {
            assertTrue(queue.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(queue.submit(finished::countDown));
            assertFalse(queue.submit(() -> rejectedTaskRan.set(true)));
            assertFalse(rejectedTaskRan.get());
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            queue.close();
        }
        assertFalse(queue.submit(() -> rejectedTaskRan.set(true)));
        assertFalse(rejectedTaskRan.get());
    }
}
