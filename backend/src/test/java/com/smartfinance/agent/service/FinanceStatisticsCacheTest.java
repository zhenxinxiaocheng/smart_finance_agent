package com.smartfinance.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.config.FinanceStatisticsCacheProperties;
import com.smartfinance.agent.mapper.TransactionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceStatisticsCacheTest {
    private final Map<String, String> stored = new HashMap<>();
    private FinanceStatisticsCache cache;
    private ValueOperations<String, String> values;
    private FinanceStatisticsCacheProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call ->
                stored.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        properties = new FinanceStatisticsCacheProperties();
        cache = new FinanceStatisticsCache(redis, new ObjectMapper().findAndRegisterModules(), properties);
    }

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

    private int read(long user, String query, AtomicInteger loads) {
        return cache.get(user, query, node -> node.intValue(), loads::incrementAndGet);
    }

    @Test
    void isolatesUsersAndFiltersAndInvalidatesAllUserQueries() {
        var loads = new AtomicInteger();
        assertThat(read(1, "income", loads)).isEqualTo(1);
        assertThat(read(1, "income", loads)).isEqualTo(1);
        assertThat(read(2, "income", loads)).isEqualTo(2);
        assertThat(read(1, "expense", loads)).isEqualTo(3);
        cache.invalidate(1L);
        assertThat(read(1, "income", loads)).isEqualTo(4);
        assertThat(read(1, "expense", loads)).isEqualTo(5);
        assertThat(read(2, "income", loads)).isEqualTo(2);
        verify(values, atLeastOnce()).set(anyString(), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void concurrentOldReadCannotRepopulateCurrentVersion() {
        int first = cache.get(1L, "income", node -> node.intValue(), () -> {
            cache.invalidate(1L);
            return 10;
        });
        assertThat(first).isEqualTo(10);
        assertThat(cache.get(1L, "income", node -> node.intValue(), () -> 20)).isEqualTo(20);
    }

    @Test
    void defersInvalidationUntilCommitAndCoalescesBatchWrites() {
        var loads = new AtomicInteger();
        read(1, "income", loads);
        String version = stored.get("finance:statistics:v1:1:version");
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidate(1L);
        cache.invalidate(1L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        assertThat(stored.get("finance:statistics:v1:1:version")).isEqualTo(version);
        // Reads in a transaction bypass the cache and do not publish their result.
        assertThat(read(1, "income", loads)).isEqualTo(2);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clear();
        assertThat(read(1, "income", loads)).isEqualTo(3);
    }

    @Test
    void rollbackDoesNotInvalidateOrPublishUncommittedTotals() {
        var loads = new AtomicInteger();
        read(1, "income", loads);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidate(1L);
        read(1, "income", loads);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clear();
        assertThat(read(1, "income", loads)).isEqualTo(1);
    }

    @Test
    void redisFailureFallsBackAndDatabaseFailureIsNotRetried() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        var loads = new AtomicInteger();
        assertThat(read(1, "income", loads)).isEqualTo(1);
        assertThat(read(1, "income", loads)).isEqualTo(2);
        verify(values, times(1)).get(anyString());
        assertThatThrownBy(() -> cache.get(1L, "income", node -> 0, () -> {
            loads.incrementAndGet();
            throw new IllegalArgumentException("DB failure");
        })).hasMessage("DB failure");
        assertThat(loads).hasValue(3);
    }

    @Test
    void cachedCategoryTotalsPreserveDecimalPrecisionAndType() {
        var transactions = mock(TransactionMapper.class);
        var service = new FinanceStatisticsService(transactions, cache);
        var start = LocalDate.of(2026, 9, 1);
        var end = start.plusDays(29);
        var total = new BigDecimal("123456789012345.67");
        when(transactions.sumByCategory(1L, start, end)).thenReturn(List.of(Map.of("category", "food", "total", total)));
        service.sumByCategory(1L, start, end);
        assertThat(service.sumByCategory(1L, start, end).get(0).get("total")).isEqualTo(total);
        verify(transactions, times(1)).sumByCategory(1L, start, end);
    }

    @Test
    void failedInvalidationIsRepairedBeforeReadingOldTotals() {
        var loads = new AtomicInteger();
        read(1, "income", loads);
        doThrow(new IllegalStateException("offline"))
                .when(values).set(eq("finance:statistics:v1:1:version"), anyString(), any(Duration.class));
        cache.invalidate(1L);
        assertThat(read(1, "income", loads)).isEqualTo(2);
        doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(eq("finance:statistics:v1:1:version"), anyString(), any(Duration.class));
        // Simulate the backoff elapsing without making the suite wait in real time.
        var retryAfter = (java.util.concurrent.atomic.AtomicLong)
                org.springframework.test.util.ReflectionTestUtils.getField(cache, "retryAfter");
        retryAfter.set(0);
        assertThat(read(1, "income", loads)).isEqualTo(3);
        assertThat(read(1, "income", loads)).isEqualTo(3);
    }

    @Test
    void aggregateRowsRoundTripDatesCountsAndDecimalAmounts() {
        var transactions = mock(TransactionMapper.class);
        var service = new FinanceStatisticsService(transactions, cache);
        var date = LocalDate.of(2026, 9, 22);
        var row = new com.smartfinance.agent.dto.TransactionStatisticsRow();
        row.setTransactionDate(date);
        row.setType("EXPENSE");
        row.setCategory("food");
        row.setAmount(new BigDecimal("123456789012345.67"));
        row.setTransactionCount(5001);
        when(transactions.statisticsByDateRange(1L, date, date)).thenReturn(List.of(row));
        service.statisticsByDateRange(1L, date, date);
        assertThat(service.statisticsByDateRange(1L, date, date)).containsExactly(row);
        verify(transactions, times(1)).statisticsByDateRange(1L, date, date);
    }
}
