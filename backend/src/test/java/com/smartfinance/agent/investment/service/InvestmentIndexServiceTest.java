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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentIndexServiceTest {

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
