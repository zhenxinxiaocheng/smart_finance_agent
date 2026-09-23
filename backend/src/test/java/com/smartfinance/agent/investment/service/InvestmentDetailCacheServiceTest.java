package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentDetailCacheProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestmentDetailCacheServiceTest {
    private final Instant now = Instant.parse("2026-09-18T00:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(now);
    private final Map<String, String> entries = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private InvestmentDetailCacheProperties properties;
    private InvestmentDetailCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> { entries.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove(call.getArgument(0)) != null);
        properties = new InvestmentDetailCacheProperties();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> time.get());
        when(clock.millis()).thenAnswer(call -> time.get().toEpochMilli());
        cache = new InvestmentDetailCacheService(redis, new ObjectMapper().findAndRegisterModules(), properties, clock);
    }

    @Test
    void freshStaleExpiredAndUserIsolationUseJsonAndPhysicalStaleTtl() {
        cache.put(7L, 11L, "context", detail());
        var fresh = cache.get(7L, 11L);
        assertThat(fresh.fresh(now)).isTrue();
        assertThat(fresh.data().getAsset().getId()).isEqualTo(11L);
        verify(values).set(eq("investment:detail:v2:7:11"), anyString(), eq(Duration.ofMinutes(10)));
        assertThat(cache.get(8L, 11L)).isNull();
        time.set(now.plusSeconds(30));
        assertThat(cache.get(7L, 11L).fresh(time.get())).isFalse();
        time.set(now.plusSeconds(600));
        assertThat(cache.get(7L, 11L)).isNull();
    }

    @Test
    void connectionFailureFailsOpenAndBacksOffWithoutRetryingEveryRequest() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get(7L, 11L)).isNull();
        assertThat(cache.get(7L, 11L)).isNull();
        cache.put(7L, 11L, "context", detail());
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        time.set(now.plusSeconds(31));
        cache.get(7L, 11L);
        verify(values, times(2)).get(anyString());
    }

    @Test
    void corruptJsonAndMismatchedOwnerAreMisses() {
        entries.put("investment:detail:v2:7:11", "broken json");
        assertThat(cache.get(7L, 11L)).isNull();
        time.set(now.plusSeconds(31));
        cache.put(8L, 11L, "context", detail());
        entries.put("investment:detail:v2:7:11", entries.get("investment:detail:v2:8:11"));
        assertThat(cache.get(7L, 11L)).isNull();
    }

    @Test
    void disabledCacheDoesNotContactRedis() {
        properties.setEnabled(false);
        cache.get(7L, 11L);
        cache.put(7L, 11L, "context", detail());
        cache.evict(7L, 11L);
        verifyNoInteractions(redis);
    }

    @Test
    void writesAndInvalidationWaitForDatabaseCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.put(7L, 11L, "context", detail());
            cache.evict(7L, 11L);
            verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(entries).isEmpty();
            verify(redis).delete("investment:detail:v2:7:11");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedEvictionDoesNotResurrectOldDataAfterReconnect() {
        cache.put(7L, 11L, "context", detail());
        time.set(now.plusSeconds(1));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        cache.evict(7L, 11L);
        time.set(now.plusSeconds(32));
        assertThat(cache.get(7L, 11L)).isNull();
    }

    @Test
    void invalidTtlIsRejected() {
        properties.setStaleTtl(Duration.ofSeconds(1));
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
    }

    private InvestmentAssetDetailResponse detail() {
        var asset = new InvestmentAssetView();
        asset.setId(11L);
        var response = new InvestmentAssetDetailResponse();
        response.setAsset(asset);
        return response;
    }
}
