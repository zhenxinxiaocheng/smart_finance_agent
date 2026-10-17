package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentQuoteCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Service
@Slf4j
public class InvestmentQuoteCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final InvestmentQuoteCacheProperties properties;
    private final Clock clock;
    private final AtomicLong retryAfter = new AtomicLong();
    private final Map<String, CompletableFuture<AnalysisServiceClient.IndexQuote>> indexLoads = new ConcurrentHashMap<>();
    private Executor indexRefreshExecutor;

    @Autowired
    void configureIndexRefreshExecutor(@Qualifier("indexQuoteRefreshExecutor") Executor executor) {
        this.indexRefreshExecutor = executor;
    }

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

    public record IndexEntry(Instant cachedAt, Instant freshUntil, Instant expiresAt,
                             AnalysisServiceClient.IndexQuote quote) {}

    /** Shared public quotes only; ownership, defaults and ordering stay in the index service. */
    public List<AnalysisServiceClient.IndexQuote> indexQuotes(List<String> indexCodes,
            Function<List<String>, List<AnalysisServiceClient.IndexQuote>> loader) {
        List<String> codes = indexCodes.stream().map(InvestmentQuoteCacheService::normalize).distinct().toList();
        Map<String, AnalysisServiceClient.IndexQuote> quotes = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>(), stale = new ArrayList<>();
        for (String code : codes) {
            IndexEntry entry = getIndex(code);
            if (entry == null) missing.add(code);
            else {
                quotes.put(code, entry.quote());
                if (!clock.instant().isBefore(entry.freshUntil())
                        || !clock.instant().isBefore(entry.cachedAt().plus(properties.getIndexFreshTtl()))) stale.add(code);
            }
        }
        if (quotes.isEmpty() && !missing.isEmpty()) {
            for (var quote : loadIndexes(missing, loader, false).join()) quotes.put(normalize(quote.indexCode()), quote);
        } else {
            stale.addAll(missing);
        }
        if (!stale.isEmpty()) loadIndexes(stale, loader, true);
        return codes.stream().map(quotes::get).filter(Objects::nonNull).toList();
    }

    private IndexEntry getIndex(String code) {
        if (!available()) return null;
        try {
            String json = redis.opsForValue().get(indexKey(code));
            if (json == null) return null;
            IndexEntry entry = mapper.readValue(json, IndexEntry.class);
            Instant now = clock.instant();
            if (entry == null || entry.cachedAt() == null || entry.freshUntil() == null || entry.expiresAt() == null
                    || entry.cachedAt().isAfter(now) || entry.freshUntil().isBefore(entry.cachedAt())
                    || entry.expiresAt().isBefore(entry.freshUntil()) || !now.isBefore(entry.expiresAt())
                    || !now.isBefore(entry.cachedAt().plus(properties.getIndexStaleTtl()))
                    || !validIndex(code, entry.quote())) return null;
            return entry;
        } catch (Exception exception) { failed(exception); return null; }
    }

    private CompletableFuture<List<AnalysisServiceClient.IndexQuote>> loadIndexes(List<String> codes,
            Function<List<String>, List<AnalysisServiceClient.IndexQuote>> loader, boolean background) {
        Map<String, CompletableFuture<AnalysisServiceClient.IndexQuote>> owned = new LinkedHashMap<>();
        List<CompletableFuture<AnalysisServiceClient.IndexQuote>> pending = new ArrayList<>();
        for (String code : codes) {
            var request = new CompletableFuture<AnalysisServiceClient.IndexQuote>();
            var existing = indexLoads.putIfAbsent(code, request);
            pending.add(existing == null ? request : existing);
            if (existing == null) owned.put(code, request);
        }
        var result = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> {
                    var successful = pending.stream().filter(request -> !request.isCompletedExceptionally())
                            .map(CompletableFuture::join).filter(Objects::nonNull).toList();
                    if (successful.isEmpty() && failure != null) throw new CompletionException(failure);
                    return successful;
                });
        if (owned.isEmpty()) return result;
        List<String> requested = List.copyOf(owned.keySet());
        Runnable fetch = () -> {
            try {
                Instant retrievedAt = clock.instant();
                Map<String, AnalysisServiceClient.IndexQuote> fetched = new LinkedHashMap<>();
                for (var quote : loader.apply(requested)) {
                    if (quote != null && owned.containsKey(normalize(quote.indexCode()))
                            && validIndex(normalize(quote.indexCode()), quote)) {
                        fetched.put(normalize(quote.indexCode()), quote);
                        putIndex(quote, retrievedAt);
                    }
                }
                owned.forEach((code, request) -> request.complete(fetched.get(code)));
            } catch (RuntimeException failure) {
                owned.values().forEach(request -> request.completeExceptionally(failure));
                if (background) log.warn("Index quote refresh failed; keeping last successful observations ({})", failure.getClass().getSimpleName());
            } finally { owned.forEach((code, request) -> indexLoads.remove(code, request)); }
        };
        if (background) {
            try { indexRefreshExecutor.execute(fetch); }
            catch (RuntimeException rejected) {
                owned.forEach((code, request) -> {
                    indexLoads.remove(code, request);
                    request.completeExceptionally(rejected);
                });
                log.debug("Index quote refresh deferred ({})", rejected.getClass().getSimpleName());
            }
        } else fetch.run();
        return result;
    }

    private void putIndex(AnalysisServiceClient.IndexQuote quote, Instant retrievedAt) {
        if (!available()) return;
        try {
            Instant expiresAt = retrievedAt.plus(properties.getIndexStaleTtl());
            Duration remaining = Duration.between(clock.instant(), expiresAt);
            if (remaining.toMillis() <= 0) return;
            redis.opsForValue().set(indexKey(quote.indexCode()), mapper.writeValueAsString(new IndexEntry(
                    retrievedAt, retrievedAt.plus(properties.getIndexFreshTtl()), expiresAt, quote)), remaining);
        } catch (Exception exception) { failed(exception); }
    }

    private static boolean validIndex(String code, AnalysisServiceClient.IndexQuote quote) {
        return quote != null && code.matches("(?:CN_INDEX:\\d{6}|GLOBAL_INDEX:[A-Z0-9._-]{1,20})")
                && code.equals(normalize(quote.indexCode())) && quote.latestPrice() != null
                && quote.latestPrice().signum() > 0 && quote.fetchedAt() != null && !quote.fetchedAt().isBlank();
    }

    private static String indexKey(String code) { return "quote:v1:INDEX:" + normalize(code); }

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
