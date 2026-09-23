package com.smartfinance.agent.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ApiRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then
                redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
                return 0
            end
            local count = tonumber(redis.call('GET', KEYS[1]))
            if count >= tonumber(ARGV[1]) then return ttl end
            redis.call('INCR', KEYS[1])
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ApiRateLimitProperties properties;
    private final Clock clock;
    private final AtomicLong retryAfter = new AtomicLong();
    private final Map<String, Counter> localCounters = new HashMap<>();

    @Autowired
    public ApiRateLimiter(StringRedisTemplate redis, ApiRateLimitProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    ApiRateLimiter(StringRedisTemplate redis, ApiRateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision fromWait(long milliseconds) {
            return new Decision(milliseconds <= 0, milliseconds <= 0 ? 0 : (milliseconds - 1) / 1000 + 1);
        }
    }

    public Decision acquire(RateLimitScope scope, String identity) {
        if (!properties.isEnabled()) return Decision.fromWait(0);
        var rule = properties.rule(scope);
        String digest = digest(identity);
        String key = "api:rate-limit:v1:" + scope.name() + ":" + rule.getLimit() + ":"
                + rule.getWindow().toMillis() + ":" + digest;
        long now = clock.millis();
        // Mirror requests during healthy operation so an outage does not reset the local allowance.
        Decision local = localAcquire(key, rule, now);
        if (now < retryAfter.get()) return local;
        try {
            Long wait = redis.execute(SCRIPT, List.of(key), String.valueOf(rule.getLimit()),
                    String.valueOf(rule.getWindow().toMillis()));
            if (wait == null || wait < 0) throw new IllegalStateException("Invalid rate limit response");
            return Decision.fromWait(wait);
        } catch (Exception exception) {
            long previous = retryAfter.get();
            if (now >= previous && retryAfter.compareAndSet(previous, now + properties.getFailureBackoff().toMillis())) {
                log.warn("Redis rate limiter unavailable; using local limits ({})", exception.getClass().getSimpleName());
            }
            return local;
        }
    }

    private synchronized Decision localAcquire(String key, ApiRateLimitProperties.Rule rule, long now) {
        Counter counter = localCounters.get(key);
        if (counter == null || now >= counter.expiresAt) {
            if (counter == null && localCounters.size() >= properties.getLocalMaxEntries()) {
                localCounters.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
                if (localCounters.size() >= properties.getLocalMaxEntries())
                    return Decision.fromWait(rule.getWindow().toMillis());
            }
            localCounters.put(key, new Counter(1, now + rule.getWindow().toMillis()));
            return Decision.fromWait(0);
        }
        if (counter.count >= rule.getLimit()) return Decision.fromWait(counter.expiresAt - now);
        counter.count++;
        return Decision.fromWait(0);
    }

    private static class Counter {
        private int count;
        private final long expiresAt;
        private Counter(int count, long expiresAt) { this.count = count; this.expiresAt = expiresAt; }
    }

    private static String digest(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
