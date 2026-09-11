package com.smartfinance.agent.investment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartfinance.agent.investment.config.InvestmentIndexProperties;
import com.smartfinance.agent.investment.dto.InvestmentIndexCreateRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexReorderRequest;
import com.smartfinance.agent.investment.dto.InvestmentIndexView;
import com.smartfinance.agent.investment.entity.InvestmentIndexDisplayOrder;
import com.smartfinance.agent.investment.entity.InvestmentIndexWatchlist;
import com.smartfinance.agent.investment.mapper.InvestmentIndexDisplayOrderMapper;
import com.smartfinance.agent.investment.mapper.InvestmentIndexWatchlistMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class InvestmentIndexServiceImpl implements InvestmentIndexService {

    private final InvestmentIndexWatchlistMapper mapper;
    private final InvestmentIndexDisplayOrderMapper orderMapper;
    private final AnalysisServiceClient analysisClient;
    private final InvestmentIndexProperties properties;

    public InvestmentIndexServiceImpl(InvestmentIndexWatchlistMapper mapper,
                                      InvestmentIndexDisplayOrderMapper orderMapper,
                                      AnalysisServiceClient analysisClient,
                                      InvestmentIndexProperties properties) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.analysisClient = analysisClient;
        this.properties = properties;
    }

    @Override
    public List<InvestmentIndexView> list(Long userId) {
        List<ItemDefinition> definitions = definitionsForUser(userId);
        Map<String, Integer> displayOrder = new LinkedHashMap<>();
        for (InvestmentIndexDisplayOrder item : orderMapper.selectList(
                new LambdaQueryWrapper<InvestmentIndexDisplayOrder>()
                        .eq(InvestmentIndexDisplayOrder::getUserId, userId)
                        .orderByAsc(InvestmentIndexDisplayOrder::getSortOrder)
        )) {
            displayOrder.put(normalizeCode(item.getIndexCode()), item.getSortOrder());
        }
        definitions.sort(Comparator.comparingInt(item ->
                displayOrder.getOrDefault(item.indexCode(), Integer.MAX_VALUE)));

        Map<String, AnalysisServiceClient.IndexQuote> quotes = new LinkedHashMap<>();
        String syncError = null;
        try {
            for (AnalysisServiceClient.IndexQuote quote : analysisClient.indexQuotes(
                    definitions.stream().map(ItemDefinition::indexCode).toList())) {
                quotes.put(normalizeCode(quote.indexCode()), quote);
            }
        } catch (RuntimeException exception) {
            syncError = exception.getMessage();
        }

        List<InvestmentIndexView> result = new ArrayList<>();
        for (ItemDefinition definition : definitions) {
            AnalysisServiceClient.IndexQuote quote = quotes.get(definition.indexCode());
            result.add(toView(definition, quote, quote == null ? syncError : null));
        }
        return result;
    }

    @Override
    @Transactional
    public void reorder(Long userId, InvestmentIndexReorderRequest request) {
        List<String> requestedCodes = request.indexCodes().stream()
                .map(InvestmentIndexServiceImpl::normalizeCode)
                .toList();
        List<String> visibleCodes = definitionsForUser(userId).stream()
                .map(ItemDefinition::indexCode)
                .toList();
        if (new HashSet<>(requestedCodes).size() != requestedCodes.size()
                || requestedCodes.size() != visibleCodes.size()
                || !new HashSet<>(requestedCodes).equals(new HashSet<>(visibleCodes))) {
            throw new IllegalArgumentException("排序必须包含完整且不重复的指数列表");
        }
        orderMapper.delete(new LambdaQueryWrapper<InvestmentIndexDisplayOrder>()
                .eq(InvestmentIndexDisplayOrder::getUserId, userId));
        for (int index = 0; index < requestedCodes.size(); index++) {
            InvestmentIndexDisplayOrder item = new InvestmentIndexDisplayOrder();
            item.setUserId(userId);
            item.setIndexCode(requestedCodes.get(index));
            item.setSortOrder(index);
            orderMapper.insert(item);
        }
    }

    @Override
    public List<InvestmentIndexView> search(String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.isEmpty()) return List.of();
        return analysisClient.searchIndexes(normalizedKeyword, properties.getSearchLimit()).stream()
                .map(quote -> toView(
                        new ItemDefinition(null, normalizeCode(quote.indexCode()),
                                quote.name(), quote.market(), isDefault(quote.indexCode())),
                        quote,
                        null
                ))
                .toList();
    }

    @Override
    @Transactional
    public InvestmentIndexView add(Long userId, InvestmentIndexCreateRequest request) {
        String code = normalizeCode(request.indexCode());
        if (isDefault(code)) throw new IllegalArgumentException("该指数已默认展示");
        InvestmentIndexWatchlist duplicate = mapper.selectOne(
                new LambdaQueryWrapper<InvestmentIndexWatchlist>()
                        .eq(InvestmentIndexWatchlist::getUserId, userId)
                        .eq(InvestmentIndexWatchlist::getIndexCode, code)
                        .last("LIMIT 1")
        );
        if (duplicate != null) throw new IllegalArgumentException("该指数已在自选列表中");
        List<AnalysisServiceClient.IndexQuote> resolved = analysisClient.indexQuotes(List.of(code));
        if (resolved.isEmpty()) throw new IllegalArgumentException("未找到该指数或行情暂不可用");
        AnalysisServiceClient.IndexQuote quote = resolved.get(0);
        InvestmentIndexWatchlist entity = new InvestmentIndexWatchlist();
        entity.setUserId(userId);
        entity.setIndexCode(code);
        entity.setDisplayName(quote.name());
        entity.setMarket(quote.market());
        mapper.insert(entity);
        return toView(
                new ItemDefinition(entity.getId(), code, quote.name(), quote.market(), false),
                quote,
                null
        );
    }

    @Override
    @Transactional
    public void remove(Long userId, Long id) {
        InvestmentIndexWatchlist item = mapper.selectById(id);
        if (item == null || !Objects.equals(item.getUserId(), userId)) {
            throw new IllegalArgumentException("指数自选不存在");
        }
        mapper.deleteById(id);
        orderMapper.delete(new LambdaQueryWrapper<InvestmentIndexDisplayOrder>()
                .eq(InvestmentIndexDisplayOrder::getUserId, userId)
                .eq(InvestmentIndexDisplayOrder::getIndexCode, item.getIndexCode()));
    }

    private List<ItemDefinition> definitionsForUser(Long userId) {
        List<InvestmentIndexWatchlist> customItems = mapper.selectList(
                new LambdaQueryWrapper<InvestmentIndexWatchlist>()
                        .eq(InvestmentIndexWatchlist::getUserId, userId)
                        .orderByAsc(InvestmentIndexWatchlist::getCreatedAt)
        );
        LinkedHashMap<String, ItemDefinition> definitions = new LinkedHashMap<>();
        for (InvestmentIndexProperties.DefaultIndex item : properties.getDefaults()) {
            String code = normalizeCode(item.getIndexCode());
            definitions.put(code, new ItemDefinition(
                    null, code, item.getDisplayName(), item.getMarket(), true
            ));
        }
        for (InvestmentIndexWatchlist item : customItems) {
            String code = normalizeCode(item.getIndexCode());
            definitions.putIfAbsent(code, new ItemDefinition(
                    item.getId(), code, item.getDisplayName(), item.getMarket(), false
            ));
        }
        return new ArrayList<>(definitions.values());
    }

    private boolean isDefault(String code) {
        String normalized = normalizeCode(code);
        return properties.getDefaults().stream()
                .map(InvestmentIndexProperties.DefaultIndex::getIndexCode)
                .map(InvestmentIndexServiceImpl::normalizeCode)
                .anyMatch(normalized::equals);
    }

    private static InvestmentIndexView toView(ItemDefinition definition,
                                               AnalysisServiceClient.IndexQuote quote,
                                               String syncError) {
        return new InvestmentIndexView(
                definition.id(),
                definition.indexCode(),
                quote == null || definition.defaultItem() ? definition.name() : quote.name(),
                quote == null ? definition.market() : quote.market(),
                definition.defaultItem(),
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.changePercent(),
                quote == null ? null : quote.changeAmount(),
                quote == null ? null : quote.previousClose(),
                quote == null ? null : quote.openPrice(),
                quote == null ? null : quote.highPrice(),
                quote == null ? null : quote.lowPrice(),
                quote == null ? null : quote.dataTime(),
                quote == null ? null : quote.fetchedAt(),
                quote == null ? "UNAVAILABLE" : "SUCCESS",
                quote == null ? blankToFallback(syncError, "行情暂不可用") : null
        );
    }

    private static String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("(?:CN_INDEX:\\d{6}|GLOBAL_INDEX:[A-Z0-9._-]{1,20})")) {
            throw new IllegalArgumentException("指数代码格式不正确");
        }
        return normalized;
    }

    private static String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ItemDefinition(Long id, String indexCode, String name,
                                  String market, boolean defaultItem) {
    }
}
