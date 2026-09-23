package com.smartfinance.agent.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiRateLimiterTest {
    @Test
    void redisDecisionUsesRemainingWindowAndDoesNotStoreRawIdentity() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L, 1501L);
        var limiter = new ApiRateLimiter(redis, new ApiRateLimitProperties(), Clock.systemUTC());
        assertThat(limiter.acquire(RateLimitScope.CHAT, "user-123").allowed()).isTrue();
        var denied = limiter.acquire(RateLimitScope.CHAT, "user-123");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(2);
        verify(redis, times(2)).execute(any(RedisScript.class),
                argThat(keys -> keys.size() == 1 && !keys.toString().contains("user-123")), any(Object[].class));
    }

    @Test
    void outageFallsBackWithIndependentUsersAndExpiresWithoutSliding() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        var now = new AtomicLong(100000);
        var clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(call -> now.get());
        var properties = new ApiRateLimitProperties();
        properties.getChat().setLimit(2);
        var limiter = new ApiRateLimiter(redis, properties, clock);
        assertThat(limiter.acquire(RateLimitScope.CHAT, "1").allowed()).isTrue();
        assertThat(limiter.acquire(RateLimitScope.CHAT, "1").allowed()).isTrue();
        assertThat(limiter.acquire(RateLimitScope.CHAT, "1").allowed()).isFalse();
        assertThat(limiter.acquire(RateLimitScope.CHAT, "2").allowed()).isTrue();
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        now.addAndGet(60000);
        assertThat(limiter.acquire(RateLimitScope.CHAT, "1").allowed()).isTrue();
    }

    @Test
    void disabledLimiterDoesNotAccessRedis() {
        var redis = mock(StringRedisTemplate.class);
        var properties = new ApiRateLimitProperties();
        properties.setEnabled(false);
        var limiter = new ApiRateLimiter(redis, properties, Clock.systemUTC());
        assertThat(limiter.acquire(RateLimitScope.LOGIN, "127.0.0.1").allowed()).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    void parallelRequestsCannotExceedLocalAllowanceDuringOutage() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        var properties = new ApiRateLimitProperties();
        properties.getChat().setLimit(3);
        var limiter = new ApiRateLimiter(redis, properties,
                Clock.fixed(java.time.Instant.parse("2026-09-22T02:00:00Z"), java.time.ZoneOffset.UTC));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 30)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> limiter.acquire(RateLimitScope.CHAT, "1").allowed())
                    .toList();
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(3);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void fallbackStorageIsBoundedAndRejectsUntrackedIdentitiesWhenFull() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        var properties = new ApiRateLimitProperties();
        properties.setLocalMaxEntries(1);
        var limiter = new ApiRateLimiter(redis, properties, Clock.systemUTC());
        assertThat(limiter.acquire(RateLimitScope.LOGIN, "one").allowed()).isTrue();
        assertThat(limiter.acquire(RateLimitScope.LOGIN, "two").allowed()).isFalse();
        assertThat(limiter.acquire(RateLimitScope.LOGIN, "one").allowed()).isTrue();
    }
}
