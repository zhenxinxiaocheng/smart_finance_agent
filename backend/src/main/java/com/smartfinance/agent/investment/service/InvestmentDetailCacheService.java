package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentDetailCacheProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class InvestmentDetailCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final InvestmentDetailCacheProperties properties;
    private final Clock clock;
    private final AtomicLong retryAfter = new AtomicLong();
    private volatile long ignoreCachedBefore;

    @Autowired
    public InvestmentDetailCacheService(StringRedisTemplate redis, ObjectMapper mapper,
                                        InvestmentDetailCacheProperties properties) {
        this(redis, mapper, properties, Clock.systemUTC());
    }

    InvestmentDetailCacheService(StringRedisTemplate redis, ObjectMapper mapper,
                                InvestmentDetailCacheProperties properties, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public record Entry(Long userId, Long assetId, Instant cachedAt, Instant freshUntil,
                        String contextKey, InvestmentAssetDetailResponse data) {
        public boolean fresh(Instant now) { return now.isBefore(freshUntil); }
    }

    public Entry get(Long userId, Long assetId) {
        if (!available()) return null;
        try {
            String json = redis.opsForValue().get(key(userId, assetId));
            if (json == null) return null;
            Entry entry = mapper.readValue(json, Entry.class);
            if (entry == null || entry.cachedAt() == null || entry.freshUntil() == null
                    || entry.data() == null || entry.data().getAsset() == null
                    || !Objects.equals(userId, entry.userId()) || !Objects.equals(assetId, entry.assetId())
                    || !Objects.equals(assetId, entry.data().getAsset().getId())
                    || entry.cachedAt().toEpochMilli() <= ignoreCachedBefore
                    || !clock.instant().isBefore(entry.cachedAt().plus(properties.getStaleTtl()))) return null;
            return entry;
        } catch (Exception exception) {
            failed(exception);
            return null;
        }
    }

    public void put(Long userId, Long assetId, String contextKey, InvestmentAssetDetailResponse data) {
        if (!available()) return;
        try {
            Instant now = clock.instant();
            String json = mapper.writeValueAsString(new Entry(userId, assetId, now,
                    now.plus(properties.getFreshTtl()), contextKey, data));
            afterCommit(() -> {
                if (!available()) return;
                try {
                    redis.opsForValue().set(key(userId, assetId), json, properties.getStaleTtl());
                } catch (Exception exception) { failed(exception); }
            });
        } catch (Exception exception) { failed(exception); }
    }

    public void evict(Long userId, Long assetId) {
        afterCommit(() -> {
            if (!properties.isEnabled()) return;
            if (!available()) {
                ignoreCachedBefore = clock.millis();
                return;
            }
            try { redis.delete(key(userId, assetId)); }
            catch (Exception exception) {
                ignoreCachedBefore = clock.millis();
                failed(exception);
            }
        });
    }

    private boolean available() {
        return properties.isEnabled() && clock.millis() >= retryAfter.get();
    }

    private void failed(Exception exception) {
        long now = clock.millis();
        long previous = retryAfter.get();
        if (now >= previous && retryAfter.compareAndSet(previous, now + properties.getFailureBackoff().toMillis())) {
            log.warn("Investment detail cache unavailable; using database ({})", exception.getClass().getSimpleName());
        }
    }

    private static String key(Long userId, Long assetId) {
        return "investment:detail:v1:" + userId + ":" + assetId;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
}
