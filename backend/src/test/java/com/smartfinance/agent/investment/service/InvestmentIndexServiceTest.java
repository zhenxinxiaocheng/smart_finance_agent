package com.smartfinance.agent.investment.service;

import com.smartfinance.agent.investment.config.InvestmentIndexProperties;
import com.smartfinance.agent.investment.dto.InvestmentIndexCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexReorderRequest;
import com.smartfinance.agent.investment.entity.InvestmentIndexDisplayOrder;
import com.smartfinance.agent.investment.entity.InvestmentIndexWatchlist;
import com.smartfinance.agent.investment.mapper.InvestmentIndexDisplayOrderMapper;
import com.smartfinance.agent.investment.mapper.InvestmentIndexWatchlistMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.config.InvestmentQuoteCacheProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InvestmentIndexServiceTest {

    @Test
    void freshPublicQuotesAreSharedWithoutSharingUserWatchlists() {
        try (var fixture = new CachedFixture()) {
            when(fixture.mapper.selectList(any())).thenReturn(
                    List.of(watchlist(9L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US")), List.of());
            when(fixture.client.indexQuotes(any())).thenReturn(List.of(
                    quote("CN_INDEX:000300", "沪深300", "CN", "4552.58", "0.10"),
                    quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29143.33", "0.23"),
                    quote("GLOBAL_INDEX:SPX", "标普500", "US", "6480.20", "0.31")));

            assertThat(fixture.service.list(7L)).hasSize(3);
            var other = fixture.service.list(8L);

            assertThat(other).extracting("indexCode").containsExactly("CN_INDEX:000300", "GLOBAL_INDEX:NDX");
            assertThat(other).extracting("latestPrice").containsExactly(new BigDecimal("4552.58"), new BigDecimal("29143.33"));
            verify(fixture.client, times(1)).indexQuotes(any());
        }
    }

    @Test
    void staleQuotesReturnImmediatelyAndOnlyOneBackgroundRefreshRuns() {
        try (var fixture = new CachedFixture()) {
            fixture.seed();
            fixture.time.set(fixture.time.get().plusSeconds(31));
            when(fixture.client.indexQuotes(any())).thenReturn(List.of(
                    quote("CN_INDEX:000300", "沪深300", "CN", "4600", "0.20"),
                    quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29200", "0.30")));

            assertThat(fixture.service.list(7L).get(0).latestPrice()).isEqualByComparingTo("4552.58");
            assertThat(fixture.service.list(7L).get(0).latestPrice()).isEqualByComparingTo("4552.58");
            verify(fixture.client, times(1)).indexQuotes(any());
            assertThat(fixture.background).hasSize(1);
            fixture.background.remove(0).run();

            assertThat(fixture.service.list(7L).get(0).latestPrice()).isEqualByComparingTo("4600");
            verify(fixture.client, times(2)).indexQuotes(any());
        }
    }

    @Test
    void failedOrPartialRefreshDoesNotEraseOrExtendTheLastSuccessfulQuote() {
        try (var fixture = new CachedFixture()) {
            fixture.seed();
            fixture.time.set(fixture.time.get().plusSeconds(31));
            Map<String, String> previous = new HashMap<>(fixture.stored);
            when(fixture.client.indexQuotes(any())).thenThrow(new IllegalStateException("source unavailable"));
            fixture.service.list(7L);
            assertThat(fixture.background).hasSize(1);
            fixture.background.remove(0).run();
            assertThat(fixture.stored).isEqualTo(previous);

            doReturn(List.of(quote("CN_INDEX:000300", "沪深300", "CN", "4600", "0.20")))
                    .when(fixture.client).indexQuotes(any());
            var stale = fixture.service.list(7L);
            assertThat(stale).allMatch(item -> item.latestPrice() != null);
            fixture.background.remove(0).run();
            var refreshed = fixture.service.list(7L);
            assertThat(refreshed.get(0).latestPrice()).isEqualByComparingTo("4600");
            assertThat(refreshed.get(1).latestPrice()).isEqualByComparingTo("29143.33");
            assertThat(fixture.stored.get("quote:v1:INDEX:GLOBAL_INDEX:NDX"))
                    .isEqualTo(previous.get("quote:v1:INDEX:GLOBAL_INDEX:NDX"));
        }
    }

    @Test
    void expiredQuotesAndUnavailableRedisUseTheOriginalProviderPath() {
        try (var fixture = new CachedFixture()) {
            fixture.seed();
            fixture.time.set(fixture.time.get().plus(Duration.ofMinutes(10)));
            fixture.service.list(7L);
            verify(fixture.client, times(2)).indexQuotes(any());
            when(fixture.values.get(anyString())).thenThrow(
                    new org.springframework.data.redis.RedisConnectionFailureException("offline"));

            assertThat(fixture.service.list(7L)).allMatch(item -> item.latestPrice() != null);
            verify(fixture.client, times(3)).indexQuotes(any());
        }
    }

    @Test
    void rejectedBackgroundRefreshNeverFallsBackToBlockingThePage() {
        try (var fixture = new CachedFixture(task -> {
            throw new java.util.concurrent.RejectedExecutionException("busy");
        })) {
            fixture.seed();
            fixture.time.set(fixture.time.get().plusSeconds(31));
            assertThat(fixture.service.list(7L)).allMatch(item -> item.latestPrice() != null);
            assertThat(fixture.service.list(7L)).allMatch(item -> item.latestPrice() != null);
            verify(fixture.client, times(1)).indexQuotes(any());
        }
    }

    @Test
    void aMissingCustomQuoteDoesNotDelayCachedDefaultIndexes() {
        try (var fixture = new CachedFixture()) {
            fixture.seed();
            when(fixture.mapper.selectList(any())).thenReturn(List.of(watchlist(9L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US")));
            when(fixture.client.indexQuotes(any())).thenReturn(List.of(quote("GLOBAL_INDEX:SPX", "标普500", "US", "6480.20", "0.31")));

            var result = fixture.service.list(7L);
            assertThat(result.get(0).latestPrice()).isEqualByComparingTo("4552.58");
            verify(fixture.client, times(1)).indexQuotes(any());
            assertThat(fixture.background).hasSize(1);
            fixture.background.remove(0).run();
            assertThat(fixture.service.list(7L).get(2).latestPrice()).isEqualByComparingTo("6480.20");
        }
    }

    @Test
    void overlappingUsersShareInFlightRefreshesForEachIndex() {
        try (var fixture = new CachedFixture()) {
            var custom = watchlist(9L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US");
            when(fixture.mapper.selectList(any())).thenReturn(List.of(custom));
            when(fixture.client.indexQuotes(any())).thenReturn(List.of(
                    quote("CN_INDEX:000300", "沪深300", "CN", "4552.58", "0.10"),
                    quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29143.33", "0.23"),
                    quote("GLOBAL_INDEX:SPX", "标普500", "US", "6480.20", "0.31")));
            fixture.service.list(7L);
            fixture.time.set(fixture.time.get().plusSeconds(31));
            clearInvocations(fixture.client);
            when(fixture.mapper.selectList(any())).thenReturn(List.of(), List.of(custom));
            fixture.service.list(8L);
            fixture.service.list(7L);
            assertThat(fixture.background).hasSize(2);
            fixture.background.remove(1).run();
            fixture.background.remove(0).run();
            @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> requested = ArgumentCaptor.forClass(List.class);
            verify(fixture.client, times(2)).indexQuotes(requested.capture());
            assertThat(requested.getAllValues().stream().flatMap(List::stream).toList())
                    .containsExactlyInAnyOrder("CN_INDEX:000300", "GLOBAL_INDEX:NDX", "GLOBAL_INDEX:SPX");
        }
    }

    @Test
    void oneFailedSharedRequestDoesNotHideAnotherSuccessfullyLoadedIndex() throws Exception {
        try (var fixture = new CachedFixture()) {
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            var started = new java.util.concurrent.CountDownLatch(1);
            var otherLoaded = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var cache = fixture.context.getBean(InvestmentQuoteCacheService.class);
            try {
                var first = executor.submit(() -> cache.indexQuotes(List.of("CN_INDEX:000300"), codes -> {
                    started.countDown();
                    try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    throw new IllegalStateException("first source failed");
                }));
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> cache.indexQuotes(List.of("CN_INDEX:000300", "GLOBAL_INDEX:NDX"), codes -> {
                    assertThat(codes).containsExactly("GLOBAL_INDEX:NDX");
                    otherLoaded.countDown();
                    return List.of(quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29143.33", "0.23"));
                }));
                assertThat(otherLoaded.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThatThrownBy(() -> first.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class);
                assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS))
                        .extracting(AnalysisServiceClient.IndexQuote::indexCode).containsExactly("GLOBAL_INDEX:NDX");
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    private static class CachedFixture implements AutoCloseable {
        final InvestmentIndexWatchlistMapper mapper = mock(InvestmentIndexWatchlistMapper.class);
        final InvestmentIndexDisplayOrderMapper orders = mock(InvestmentIndexDisplayOrderMapper.class);
        final AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") final ValueOperations<String, String> values = mock(ValueOperations.class);
        final Map<String, String> stored = new HashMap<>();
        final List<Runnable> background = new ArrayList<>();
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-15T02:00:00Z"));
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final InvestmentIndexServiceImpl service = new InvestmentIndexServiceImpl(mapper, orders, client, properties());

        CachedFixture() { this(null); }

        CachedFixture(Executor executor) {
            when(mapper.selectList(any())).thenReturn(List.of());
            when(orders.selectList(any())).thenReturn(List.of());
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
            doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(values).set(anyString(), anyString(), any(Duration.class));
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenAnswer(call -> time.get());
            when(clock.millis()).thenAnswer(call -> time.get().toEpochMilli());
            context.registerBean("indexQuoteRefreshExecutor", Executor.class, () -> executor == null ? background::add : executor);
            context.registerBean(InvestmentQuoteCacheService.class, () -> new InvestmentQuoteCacheService(
                    redis, new ObjectMapper().findAndRegisterModules(), new InvestmentQuoteCacheProperties(), clock));
            context.registerBean(InvestmentIndexServiceImpl.class, () -> service);
            context.refresh();
        }

        void seed() {
            when(client.indexQuotes(any())).thenReturn(List.of(
                    quote("CN_INDEX:000300", "沪深300", "CN", "4552.58", "0.10"),
                    quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29143.33", "0.23")));
            service.list(7L);
        }

        @Override public void close() { context.close(); }
    }

    @Test
    void listKeepsConfiguredDefaultsSeparateFromUserHoldings() {
        InvestmentIndexWatchlistMapper mapper = mock(InvestmentIndexWatchlistMapper.class);
        InvestmentIndexDisplayOrderMapper orderMapper = mock(InvestmentIndexDisplayOrderMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        InvestmentIndexProperties properties = properties();
        InvestmentIndexWatchlist custom = watchlist(9L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US");
        when(mapper.selectList(any())).thenReturn(List.of(custom));
        when(orderMapper.selectList(any())).thenReturn(List.of(
                order(7L, "GLOBAL_INDEX:SPX", 0),
                order(7L, "CN_INDEX:000300", 1),
                order(7L, "GLOBAL_INDEX:NDX", 2)
        ));
        when(client.indexQuotes(List.of(
                "GLOBAL_INDEX:SPX", "CN_INDEX:000300", "GLOBAL_INDEX:NDX"
        ))).thenReturn(List.of(
                quote("CN_INDEX:000300", "沪深300", "CN", "4552.58", "0.10"),
                quote("GLOBAL_INDEX:NDX", "纳斯达克100", "US", "29143.33", "0.23"),
                quote("GLOBAL_INDEX:SPX", "标普500", "US", "6480.20", "0.31")
        ));

        var result = new InvestmentIndexServiceImpl(mapper, orderMapper, client, properties).list(7L);

        assertThat(result).extracting("indexCode")
                .containsExactly("GLOBAL_INDEX:SPX", "CN_INDEX:000300", "GLOBAL_INDEX:NDX");
        assertThat(result.get(1).defaultItem()).isTrue();
        assertThat(result.get(0).id()).isEqualTo(9L);
        assertThat(result).allMatch(item -> item.latestPrice() != null);
    }

    @Test
    void addValidatesTheIndexThroughMarketDataAndRejectsDuplicates() {
        InvestmentIndexWatchlistMapper mapper = mock(InvestmentIndexWatchlistMapper.class);
        InvestmentIndexDisplayOrderMapper orderMapper = mock(InvestmentIndexDisplayOrderMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(client.indexQuotes(List.of("GLOBAL_INDEX:SPX")))
                .thenReturn(List.of(quote("GLOBAL_INDEX:SPX", "标普500", "US", "6480.20", "0.31")));
        when(mapper.insert(any())).thenAnswer(invocation -> {
            InvestmentIndexWatchlist entity = invocation.getArgument(0);
            entity.setId(12L);
            return 1;
        });
        InvestmentIndexService service = new InvestmentIndexServiceImpl(mapper, orderMapper, client, properties());

        var added = service.add(7L, new InvestmentIndexCreateRequest("global_index:spx"));

        assertThat(added.id()).isEqualTo(12L);
        assertThat(added.indexCode()).isEqualTo("GLOBAL_INDEX:SPX");
        verify(mapper).insert(any(InvestmentIndexWatchlist.class));

        when(mapper.selectOne(any())).thenReturn(watchlist(12L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US"));
        assertThatThrownBy(() -> service.add(7L, new InvestmentIndexCreateRequest("GLOBAL_INDEX:SPX")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已在自选");
    }

    @Test
    void reorderPersistsEveryVisibleIndexIncludingConfiguredDefaults() {
        InvestmentIndexWatchlistMapper mapper = mock(InvestmentIndexWatchlistMapper.class);
        InvestmentIndexDisplayOrderMapper orderMapper = mock(InvestmentIndexDisplayOrderMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                watchlist(9L, 7L, "GLOBAL_INDEX:SPX", "标普500", "US")
        ));
        InvestmentIndexService service = new InvestmentIndexServiceImpl(
                mapper, orderMapper, client, properties()
        );

        service.reorder(7L, new InvestmentIndexReorderRequest(List.of(
                "GLOBAL_INDEX:NDX", "GLOBAL_INDEX:SPX", "CN_INDEX:000300"
        )));

        ArgumentCaptor<InvestmentIndexDisplayOrder> captor =
                ArgumentCaptor.forClass(InvestmentIndexDisplayOrder.class);
        verify(orderMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(
                InvestmentIndexDisplayOrder::getIndexCode,
                InvestmentIndexDisplayOrder::getSortOrder
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple("GLOBAL_INDEX:NDX", 0),
                org.assertj.core.groups.Tuple.tuple("GLOBAL_INDEX:SPX", 1),
                org.assertj.core.groups.Tuple.tuple("CN_INDEX:000300", 2)
        );
    }

    @Test
    void reorderRejectsAnIncompleteVisibleIndexSet() {
        InvestmentIndexWatchlistMapper mapper = mock(InvestmentIndexWatchlistMapper.class);
        InvestmentIndexDisplayOrderMapper orderMapper = mock(InvestmentIndexDisplayOrderMapper.class);
        AnalysisServiceClient client = mock(AnalysisServiceClient.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        InvestmentIndexService service = new InvestmentIndexServiceImpl(
                mapper, orderMapper, client, properties()
        );

        assertThatThrownBy(() -> service.reorder(7L, new InvestmentIndexReorderRequest(
                List.of("GLOBAL_INDEX:NDX")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完整");
    }

    private static InvestmentIndexProperties properties() {
        InvestmentIndexProperties properties = new InvestmentIndexProperties();
        properties.setSearchLimit(20);
        properties.setDefaults(List.of(
                new InvestmentIndexProperties.DefaultIndex("CN_INDEX:000300", "沪深300", "CN"),
                new InvestmentIndexProperties.DefaultIndex("GLOBAL_INDEX:NDX", "纳斯达克100", "US")
        ));
        return properties;
    }

    private static InvestmentIndexWatchlist watchlist(Long id, Long userId, String code, String name, String market) {
        InvestmentIndexWatchlist item = new InvestmentIndexWatchlist();
        item.setId(id);
        item.setUserId(userId);
        item.setIndexCode(code);
        item.setDisplayName(name);
        item.setMarket(market);
        item.setCreatedAt(LocalDateTime.of(2026, 9, 3, 16, 0));
        item.setUpdatedAt(LocalDateTime.of(2026, 9, 3, 16, 0));
        return item;
    }

    private static InvestmentIndexDisplayOrder order(Long userId, String code, int sortOrder) {
        InvestmentIndexDisplayOrder item = new InvestmentIndexDisplayOrder();
        item.setUserId(userId);
        item.setIndexCode(code);
        item.setSortOrder(sortOrder);
        return item;
    }

    private static AnalysisServiceClient.IndexQuote quote(
            String code, String name, String market, String price, String percent
    ) {
        return new AnalysisServiceClient.IndexQuote(
                code, name, market, new BigDecimal(price), new BigDecimal(percent),
                null, null, null, null, null, null, "2026-09-03 16:00:00", "AKSHARE"
        );
    }
}
