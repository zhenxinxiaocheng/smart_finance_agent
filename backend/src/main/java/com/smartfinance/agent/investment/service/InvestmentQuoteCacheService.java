package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentQuoteCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class InvestmentQuoteCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final InvestmentQuoteCacheProperties properties;
    private final Clock clock;
    private final AtomicLong retryAfter = new AtomicLong();

    @Autowired
    public InvestmentQuoteCacheService(StringRedisTemplate redis, ObjectMapper mapper,
                                       InvestmentQuoteCacheProperties properties) {
        this(redis, mapper, properties, Clock.systemUTC());
    }

    InvestmentQuoteCacheService(StringRedisTemplate redis, ObjectMapper mapper,
                               InvestmentQuoteCacheProperties properties, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public record Entry(Instant cachedAt, Instant expiresAt, AnalysisServiceClient.RealtimeQuote quote) {}

    public Entry get(String market, String code) {
        if (!available()) return null;
        try {
            String json = redis.opsForValue().get(key(market, code));
            if (json == null) return null;
            Entry entry = mapper.readValue(json, Entry.class);
            Instant now = clock.instant();
            if (entry == null || entry.cachedAt() == null || entry.expiresAt() == null
                    || entry.cachedAt().isAfter(now) || !now.isBefore(entry.expiresAt())
                    || !now.isBefore(entry.cachedAt().plus(properties.getTtl()))
                    || !validQuote(market, code, entry.quote())) return null;
            log.debug("Investment quote cache HIT");
            return entry;
        } catch (Exception exception) {
            failed(exception);
            return null;
        }
    }

    public void put(String market, String code, AnalysisServiceClient.RealtimeQuote quote, Instant retrievedAt) {
        if (!available() || !validQuote(market, code, quote) || retrievedAt == null) return;
        try {
            Instant expiresAt = retrievedAt.plus(properties.getTtl());
            Duration remaining = Duration.between(clock.instant(), expiresAt);
            if (remaining.toMillis() <= 0) return;
            redis.opsForValue().set(key(market, code),
                    mapper.writeValueAsString(new Entry(retrievedAt, expiresAt, quote)), remaining);
        } catch (Exception exception) { failed(exception); }
    }

    private static boolean validQuote(String market, String code, AnalysisServiceClient.RealtimeQuote quote) {
        return quote != null && !normalize(market).isEmpty() && !normalize(code).isEmpty()
                && normalize(market).equals(normalize(quote.market()))
                && normalize(code).equals(normalize(quote.code()))
                && quote.latestPrice() != null && quote.latestPrice().signum() > 0
                && quote.dataDate() != null && quote.fetchedAt() != null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(String market, String code) {
        return "quote:v1:STOCK:" + normalize(market) + ":" + normalize(code);
    }

    private boolean available() {
        return properties.isEnabled() && clock.millis() >= retryAfter.get();
    }

    private void failed(Exception exception) {
        long now = clock.millis();
        long previous = retryAfter.get();
        if (now >= previous && retryAfter.compareAndSet(previous, now + properties.getFailureBackoff().toMillis())) {
            log.warn("Investment quote cache unavailable; using quote provider ({})", exception.getClass().getSimpleName());
        }
    }
}
