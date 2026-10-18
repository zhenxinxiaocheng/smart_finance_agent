package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentDetailCacheProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class InvestmentDetailCacheService {
    private static final DefaultRedisScript<Long> WRITE_IF_CURRENT = new DefaultRedisScript<>("""
            local incoming = cjson.decode(ARGV[1])
            local stored = redis.call('GET', KEYS[1])
            if stored then
                local valid, previous = pcall(cjson.decode, stored)
                if valid and type(previous) == 'table' and type(previous.cachedAt) == 'string' and
                   (previous.cachedAt > incoming.cachedAt or
                    (previous.cachedAt == incoming.cachedAt and incoming.data ~= cjson.null)) then
                    return 0
                end
            end
            redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
            return 1
            """, Long.class);
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

    public record Entry(Long userId, Long assetId,
                        @JsonFormat(shape = JsonFormat.Shape.STRING,
                                pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'", timezone = "UTC") Instant cachedAt,
                        Instant freshUntil,
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

    public void put(Long userId, Long assetId, String contextKey, InvestmentAssetDetailResponse data,
                    Instant checkedAt) {
        if (!available()) return;
        try {
            Instant now = clock.instant();
            Instant validatedAt = checkedAt.isAfter(now) ? now : checkedAt;
            Instant freshUntil = validatedAt.plus(properties.getFreshTtl());
            if (!now.isBefore(freshUntil)) return;
            String json = mapper.writeValueAsString(new Entry(userId, assetId, validatedAt,
                    freshUntil, contextKey, data));
            afterCommit(() -> {
                if (!available() || !clock.instant().isBefore(freshUntil)) return;
                try {
                    write(userId, assetId, json, properties.getStaleTtl());
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
            try {
                Instant now = clock.instant();
                String tombstone = mapper.writeValueAsString(new Entry(userId, assetId, now, now, null, null));
                write(userId, assetId, tombstone, properties.getFreshTtl());
            }
            catch (Exception exception) {
                ignoreCachedBefore = clock.millis();
                failed(exception);
            }
        });
    }

    private void write(Long userId, Long assetId, String json, Duration ttl) {
        redis.execute(WRITE_IF_CURRENT, List.of(key(userId, assetId)), json, Long.toString(ttl.toMillis()));
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
        return "investment:detail:v4:" + userId + ":" + assetId;
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
