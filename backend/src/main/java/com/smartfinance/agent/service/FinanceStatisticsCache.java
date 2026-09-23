package com.smartfinance.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.config.FinanceStatisticsCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Service
public class FinanceStatisticsCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final FinanceStatisticsCacheProperties properties;
    private final AtomicLong retryAfter = new AtomicLong();
    private final ConcurrentHashMap<Long, Object> pendingInvalidations = new ConcurrentHashMap<>();

    public FinanceStatisticsCache(StringRedisTemplate redis, ObjectMapper mapper,
                                  FinanceStatisticsCacheProperties properties) {
        this.redis = redis;
        this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.properties = properties;
    }

    public <T> T get(Long userId, Object query, Function<JsonNode, T> decode, Supplier<T> loader) {
        // Transactional reads must see their own writes and must never publish uncommitted data.
        if (!properties.isEnabled() || userId == null || System.currentTimeMillis() < retryAfter.get()
                || TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        String key;
        try {
            Object pending = pendingInvalidations.get(userId);
            if (pending != null) rotateVersion(userId, pending);
            String versionKey = versionKey(userId);
            String version = redis.opsForValue().get(versionKey);
            if (version == null) {
                String candidate = UUID.randomUUID().toString();
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(versionKey, candidate,
                        properties.getTtl().multipliedBy(2)))) version = candidate;
                else version = redis.opsForValue().get(versionKey);
            }
            if (version == null) throw new IllegalStateException("Statistics version unavailable");
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(query).getBytes(StandardCharsets.UTF_8)));
            key = "finance:statistics:v1:" + userId + ":" + version + ":" + fingerprint;
            String cached = redis.opsForValue().get(key);
            if (cached != null) return decode.apply(mapper.readTree(cached));
        } catch (Exception exception) {
            failed(exception);
            return loader.get();
        }
        T result = loader.get();
        try {
            // The version is captured before the DB read. An overlapping invalidation makes
            // this old key unreachable, even if a slow query finishes after a write commits.
            if (result != null) redis.opsForValue().set(key, mapper.writeValueAsString(result), properties.getTtl());
        } catch (Exception exception) { failed(exception); }
        return result;
    }

    public void invalidate(Long userId) {
        if (!properties.isEnabled() || userId == null) return;
        Runnable action = () -> {
            Object pending = new Object();
            pendingInvalidations.put(userId, pending);
            try {
                // Always attempt invalidation, even while reads are in failure backoff.
                rotateVersion(userId, pending);
            } catch (Exception exception) { failed(exception); }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            boolean registered = TransactionSynchronizationManager.getSynchronizations().stream()
                    .anyMatch(sync -> sync instanceof Invalidation invalidation && invalidation.userId().equals(userId));
            if (!registered) TransactionSynchronizationManager.registerSynchronization(new Invalidation(userId, action));
        } else action.run();
    }

    private record Invalidation(Long userId, Runnable action) implements TransactionSynchronization {
        @Override public void afterCommit() { action.run(); }
    }

    private void rotateVersion(Long userId, Object pending) {
        // A fresh token on every attempt prevents retries from resurrecting a previous version.
        redis.opsForValue().set(versionKey(userId), UUID.randomUUID().toString(), properties.getTtl().multipliedBy(2));
        pendingInvalidations.remove(userId, pending);
    }

    private String versionKey(Long userId) { return "finance:statistics:v1:" + userId + ":version"; }

    private void failed(Exception exception) {
        long now = System.currentTimeMillis();
        long previous = retryAfter.get();
        if (now >= previous && retryAfter.compareAndSet(previous, now + properties.getFailureBackoff().toMillis())) {
            log.warn("Statistics cache unavailable; using database ({})", exception.getClass().getSimpleName());
        }
    }
}
