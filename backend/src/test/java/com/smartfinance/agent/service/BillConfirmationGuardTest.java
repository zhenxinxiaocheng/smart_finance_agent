package com.smartfinance.agent.service;

import com.smartfinance.agent.config.BillConfirmationGuardProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillConfirmationGuardTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private BillConfirmationGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        guard = new BillConfirmationGuard(redis, new BillConfirmationGuardProperties());
    }

    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test
    void busyLeaseIsRejectedInsteadOfFallingBack() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> guard.acquire(7L, 100L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("正在确认");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void redisFailureFallsBackToDatabaseAndUsesBackoff() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("offline"));
        guard.acquire(7L, 100L).close();
        guard.acquire(7L, 100L).close();
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void leaseIsReleasedWithItsOwnTokenOnlyAfterTransactionCompletion() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var lease = guard.acquire(7L, 100L);
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq("bill:confirmation:v1:7:100"), token.capture(), eq(Duration.ofSeconds(30)));
        lease.close();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(redis).execute(any(RedisScript.class), eq(List.of("bill:confirmation:v1:7:100")), eq(token.getValue()));
    }

    @Test
    void rollbackReleasesLeaseSoTheRequestCanRetry() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        guard.acquire(7L, 100L).close();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(redis).execute(any(RedisScript.class), eq(List.of("bill:confirmation:v1:7:100")), anyString());
    }
}
