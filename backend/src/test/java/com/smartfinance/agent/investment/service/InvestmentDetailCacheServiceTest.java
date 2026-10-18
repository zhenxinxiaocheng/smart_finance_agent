package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentDetailCacheProperties;
import com.smartfinance.agent.investment.dto.InvestmentAssetDetailResponse;
import com.smartfinance.agent.investment.dto.InvestmentAssetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
    private Clock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(call -> {
            String key = ((List<String>) call.getArgument(1)).get(0);
            String json = call.getArgument(2);
            var incoming = mapper.readTree(json);
            if (entries.containsKey(key)) {
                var previous = mapper.readTree(entries.get(key));
                int order = previous.path("cachedAt").asText().compareTo(incoming.path("cachedAt").asText());
                if (order > 0 || order == 0 && !incoming.path("data").isNull()) return 0L;
            }
            entries.put(key, json);
            return 1L;
        });
        properties = new InvestmentDetailCacheProperties();
        clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> time.get());
        when(clock.millis()).thenAnswer(call -> time.get().toEpochMilli());
        cache = new InvestmentDetailCacheService(redis, new ObjectMapper().findAndRegisterModules(), properties, clock);
    }

    @Test
    void freshStaleExpiredAndUserIsolationUseJsonAndPhysicalStaleTtl() {
        cache.put(7L, 11L, "context", detail(), time.get());
        var fresh = cache.get(7L, 11L);
        assertThat(fresh.fresh(now)).isTrue();
        assertThat(fresh.data().getAsset().getId()).isEqualTo(11L);
        verify(redis).execute(any(RedisScript.class), eq(List.of("investment:detail:v4:7:11")),
                anyString(), eq("600000"));
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
        cache.put(7L, 11L, "context", detail(), time.get());
        verify(values, times(1)).get(anyString());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        time.set(now.plusSeconds(31));
        cache.get(7L, 11L);
        verify(values, times(2)).get(anyString());
    }

    @Test
    void corruptJsonAndMismatchedOwnerAreMisses() {
        entries.put("investment:detail:v4:7:11", "broken json");
        assertThat(cache.get(7L, 11L)).isNull();
        time.set(now.plusSeconds(31));
        cache.put(8L, 11L, "context", detail(), time.get());
        entries.put("investment:detail:v4:7:11", entries.get("investment:detail:v4:8:11"));
        assertThat(cache.get(7L, 11L)).isNull();
    }

    @Test
    void disabledCacheDoesNotContactRedis() {
        properties.setEnabled(false);
        cache.get(7L, 11L);
        cache.put(7L, 11L, "context", detail(), time.get());
        cache.evict(7L, 11L);
        verifyNoInteractions(redis);
    }

    @Test
    void writesAndInvalidationWaitForDatabaseCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.put(7L, 11L, "context", detail(), time.get());
            cache.evict(7L, 11L);
            verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(cache.get(7L, 11L)).isNull();
            verify(redis, times(2)).execute(any(RedisScript.class),
                    eq(List.of("investment:detail:v4:7:11")), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void failedEvictionDoesNotResurrectOldDataAfterReconnect() {
        cache.put(7L, 11L, "context", detail(), time.get());
        time.set(now.plusSeconds(1));
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        cache.evict(7L, 11L);
        time.set(now.plusSeconds(32));
        assertThat(cache.get(7L, 11L)).isNull();
    }

    @Test
    void slowDetailReconstructionDoesNotExtendTheValidationWindow() {
        time.set(now.plusSeconds(25));
        cache.put(7L, 11L, "context", detail(), now);

        assertThat(cache.get(7L, 11L).freshUntil()).isEqualTo(now.plusSeconds(30));
        time.set(now.plusSeconds(30));
        assertThat(cache.get(7L, 11L).fresh(time.get())).isFalse();
    }

    @Test
    void expiredValidationIsNotCachedAfterADelayedCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.put(7L, 11L, "context", detail(), now);
            time.set(now.plusSeconds(30));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(entries).isEmpty();
            verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void invalidationAndNewerDetailsRejectLateFills() {
        assertLateFillsAreRejected(cache, cache);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_TEST_PORT", matches = "[0-9]+")
    void isolatedRedisExecutesTheAtomicInvalidationProtocol() {
        var connections = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("REDIS_TEST_PORT")));
        connections.afterPropertiesSet();
        try {
            var realRedis = new StringRedisTemplate(connections);
            assertThat(System.getenv("REDIS_TEST_OWNER")).isNotBlank();
            assertThat(realRedis.opsForValue().get("sfa:isolated-test-owner"))
                    .isEqualTo(System.getenv("REDIS_TEST_OWNER"));
            var realCache = new InvestmentDetailCacheService(realRedis,
                    new ObjectMapper().findAndRegisterModules(), properties, clock);
            try {
                var otherInstance = new InvestmentDetailCacheService(realRedis,
                        new ObjectMapper().findAndRegisterModules(), properties, clock);
                assertLateFillsAreRejected(realCache, otherInstance);
            } finally {
                realRedis.delete("investment:detail:v4:7:11");
            }
        } finally {
            connections.destroy();
        }
    }

    private void assertLateFillsAreRejected(InvestmentDetailCacheService target,
                                           InvestmentDetailCacheService invalidator) {
        time.set(now.plusSeconds(1));
        invalidator.evict(7L, 11L);
        time.set(now.plusSeconds(2));
        target.put(7L, 11L, "old", detail(), now);
        assertThat(target.get(7L, 11L)).isNull();

        var current = detail();
        current.getAsset().setName("current");
        target.put(7L, 11L, "new", current, time.get());
        target.put(7L, 11L, "old", detail(), now);
        assertThat(target.get(7L, 11L).data().getAsset().getName()).isEqualTo("current");
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
