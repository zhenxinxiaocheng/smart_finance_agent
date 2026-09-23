package com.smartfinance.agent.investment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentQuoteCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestmentQuoteCacheServiceTest {
    private final Instant start = Instant.parse("2026-09-22T02:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(start);
    private final Map<String, String> stored = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private InvestmentQuoteCacheProperties properties;
    private InvestmentQuoteCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(clock.millis()).thenAnswer(call -> now.get().toEpochMilli());
        properties = new InvestmentQuoteCacheProperties();
        cache = new InvestmentQuoteCacheService(redis, new ObjectMapper().findAndRegisterModules(), properties, clock);
    }

    @Test
    void sharesPublicQuoteByMarketAndCodeAndExpiresWithoutSlidingTtl() {
        cache.put("SSE", "600000", quote("SSE", "600000"), start);
        var hit = cache.get("sse", "600000");
        assertThat(hit.quote().latestPrice()).isEqualByComparingTo("10");
        assertThat(hit.expiresAt()).isEqualTo(start.plusSeconds(3));
        assertThat(cache.get("SZSE", "600000")).isNull();
        assertThat(cache.get("SSE", "600001")).isNull();
        verify(values).set(eq("quote:v1:STOCK:SSE:600000"), anyString(), eq(Duration.ofSeconds(3)));
        now.set(start.plusSeconds(3));
        assertThat(cache.get("SSE", "600000")).isNull();
    }

    @Test
    void delayedPublishOnlyUsesRemainingLifetime() {
        now.set(start.plusSeconds(2));
        cache.put("SSE", "600000", quote("SSE", "600000"), start);
        verify(values).set(anyString(), anyString(), eq(Duration.ofSeconds(1)));
        now.set(start.plusSeconds(4));
        cache.put("SSE", "600000", quote("SSE", "600000"), start);
        verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void malformedOrWrongInstrumentIsNotUsed() {
        cache.put("SSE", "600000", quote("SSE", "600001"), start);
        verifyNoInteractions(redis);
        stored.put("quote:v1:STOCK:SSE:600000", "invalid-json");
        assertThat(cache.get("SSE", "600000")).isNull();
    }

    @Test
    void disabledAndUnavailableRedisFailOpenWithBackoff() {
        properties.setEnabled(false);
        cache.get("SSE", "600000");
        cache.put("SSE", "600000", quote("SSE", "600000"), start);
        verifyNoInteractions(redis);
        properties.setEnabled(true);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get("SSE", "600000")).isNull();
        assertThat(cache.get("SSE", "600000")).isNull();
        cache.put("SSE", "600000", quote("SSE", "600000"), start);
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        now.set(start.plusSeconds(30));
        when(values.get(anyString())).thenReturn(null);
        assertThat(cache.get("SSE", "600000")).isNull();
        verify(values, times(2)).get(anyString());
    }

    @Test
    void writeFailureDoesNotEscapeAndInvalidConfigurationIsRejected() {
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertThatCode(() -> cache.put("SSE", "600000", quote("SSE", "600000"), start))
                .doesNotThrowAnyException();
        properties.setTtl(Duration.ZERO);
        assertThatIllegalArgumentException().isThrownBy(properties::validate);
    }

    private AnalysisServiceClient.RealtimeQuote quote(String market, String code) {
        return new AnalysisServiceClient.RealtimeQuote(code, market, BigDecimal.TEN,
                LocalDate.of(2026, 9, 22), LocalDateTime.of(2026, 9, 22, 10, 0),
                null, null, null, null, null, null, null, null, null, null, null, "TEST", List.of());
    }
}
