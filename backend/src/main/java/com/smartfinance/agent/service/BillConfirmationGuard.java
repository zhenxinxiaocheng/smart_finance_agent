package com.smartfinance.agent.service;

import com.smartfinance.agent.config.BillConfirmationGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class BillConfirmationGuard {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final BillConfirmationGuardProperties properties;
    private final AtomicLong retryAfter = new AtomicLong();

    public BillConfirmationGuard(StringRedisTemplate redis, BillConfirmationGuardProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public interface Lease extends AutoCloseable { @Override void close(); }

    public Lease acquire(Long userId, Long billId) {
        if (!properties.isEnabled() || System.currentTimeMillis() < retryAfter.get()) return () -> {};
        String key = "bill:confirmation:v1:" + userId + ":" + billId;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, properties.getLeaseTtl());
            if (acquired == null) throw new IllegalStateException("Missing lease response");
        } catch (Exception exception) {
            failed(exception);
            return () -> {}; // The database transaction and row locks are the correctness boundary.
        }
        if (!acquired) throw new ResponseStatusException(HttpStatus.CONFLICT, "该账单正在确认中，请稍后查看结果或重试");
        var released = new AtomicBoolean();
        Lease release = () -> {
            if (!released.compareAndSet(false, true)) return;
            try { redis.execute(RELEASE, List.of(key), token); }
            catch (Exception exception) { failed(exception); }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { release.close(); }
            });
            return () -> {};
        }
        return release;
    }

    private void failed(Exception exception) {
        long now = System.currentTimeMillis();
        long previous = retryAfter.get();
        if (now >= previous && retryAfter.compareAndSet(previous, now + properties.getFailureBackoff().toMillis()))
            log.warn("Bill confirmation Redis guard unavailable; using database locks ({})", exception.getClass().getSimpleName());
    }
}
