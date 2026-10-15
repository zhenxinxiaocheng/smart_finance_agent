package com.smartfinance.agent.investment.quant.workbench.market;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.entity.ProductDailyQuote;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkbenchMarketService {
    private final WorkbenchMarketRepository marketRepository;
    private final com.smartfinance.agent.investment.service.AnalysisServiceClient marketProvider;
    private final com.smartfinance.agent.investment.service.InvestmentDataQualityService
            dataQualityService;
    private final com.smartfinance.agent.investment.service.MarketDataService marketData;

    public WorkbenchMarketService(
            WorkbenchMarketRepository marketRepository,
            com.smartfinance.agent.investment.service.AnalysisServiceClient marketProvider,
            com.smartfinance.agent.investment.service.InvestmentDataQualityService
                    dataQualityService,
            com.smartfinance.agent.investment.service.MarketDataService marketData) {
        this.marketRepository = marketRepository;
        this.marketProvider = marketProvider;
        this.dataQualityService = dataQualityService;
        this.marketData = marketData;
    }

    public List<Map<String, Object>> assets(Long u) {
        var owned =
                marketRepository.ownedAssets(u).stream()
                        .filter(
                                row ->
                                        DOMESTIC_MARKETS.contains(
                                                str(row.get("market")).toUpperCase(Locale.ROOT)))
                        .toList();
        var summaries =
                marketData.getQuoteSummaries(
                        owned.stream()
                                .map(row -> ((Number) row.get("product_id")).longValue())
                                .toList());
        return owned.stream()
                .map(
                        row -> {
                            Map<String, Object> complete = new LinkedHashMap<>(row);
                            complete.putAll(
                                    summaries.get(((Number) row.get("product_id")).longValue()));
                            Map<String, Object> out = new LinkedHashMap<>();
                            complete.forEach((key, value) -> out.put(camel(key), value));
                            out.put("assetClass", assetClass(row));
                            return out;
                        })
                .toList();
    }

    public String assetClass(Map<String, Object> r) {
        String type = str(r.get("product_type")).toUpperCase(Locale.ROOT);
        // ETF feeder funds remain OTC funds; a name containing ETF is not an exchange listing.
        return Set.of("FUND", "MUTUAL_FUND").contains(type) ? "FUND" : type;
    }

    public List<Long> productIds(Map<String, Object> pool) {
        require(pool.get("productIds") instanceof List<?>, "请选择市场证券");
        List<Long> ids = new ArrayList<>();
        for (Object value : (List<?>) pool.get("productIds")) {
            require(value instanceof Number && ((Number) value).longValue() > 0, "市场证券编号无效");
            ids.add(((Number) value).longValue());
        }
        return ids;
    }

    public Map<Long, Map<String, Object>> productsById(List<Long> ids) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> part = ids.subList(from, Math.min(from + 500, ids.size()));
            for (var product : marketRepository.products(part))
                result.put(((Number) product.get("product_id")).longValue(), product);
        }
        return result;
    }

    public List<Long> presetProducts(String preset) {
        require(
                Set.of("ALL_A", "CSI300", "CSI500", "CSI1000", "SP500", "NASDAQ100")
                        .contains(preset),
                "未知预定义资产池");
        if ("ALL_A".equals(preset)) return marketRepository.activeStockIds();
        require(!Set.of("SP500", "NASDAQ100").contains(preset), "该指数成分数据源尚不可用，不能伪造资产池");
        require(marketProvider != null, "指数成分数据源不可用");
        List<String> codes = marketProvider.marketUniverseMembers(preset);
        require(!codes.isEmpty(), "指数成分为空");
        Set<String> requested = new HashSet<>(codes);
        List<Long> result = new ArrayList<>();
        for (var row : marketRepository.activeStocks())
            if (requested.remove(str(row.get("code"))))
                result.add(((Number) row.get("id")).longValue());
        require(requested.isEmpty(), "市场目录缺少指数成分，请先完成目录同步");
        return result;
    }

    public void persistMembership(
            Long user, String universeId, int revision, Map<String, Object> payload) {
        List<Long> ids = productIds(payload);
        String snapshotId = id();
        marketRepository.insertMembershipSnapshot(
                snapshotId,
                user,
                universeId,
                revision,
                LocalDate.parse(str(payload.get("membershipAsOfDate"))),
                payload.get("membershipCapability"),
                payload.get("presetKey") == null ? "MANUAL" : payload.get("presetKey"),
                LocalDateTime.now());
        List<Object[]> members =
                ids.stream().map(productId -> new Object[] {snapshotId, productId}).toList();
        marketRepository.insertMembership(members);
    }

    public List<Map<String, Object>> members(Long user, Map<String, Object> pool) {
        if (pool.containsKey("productIds")) {
            List<Long> ids = productIds(pool);
            Map<Long, Map<String, Object>> byId = productsById(ids);
            require(byId.size() == ids.size(), "资产池中有市场证券已不存在");
            for (var product : byId.values())
                require(
                        DOMESTIC_MARKETS.contains(
                                str(product.get("market")).toUpperCase(Locale.ROOT)),
                        "首版仅支持国内市场标的");
            return ids.stream()
                    .map(byId::get)
                    .map(
                            row -> {
                                Map<String, Object> result = new LinkedHashMap<>(row);
                                result.put("assetClass", assetClass(row));
                                return result;
                            })
                    .toList();
        }
        List<?> assetIds = (List<?>) pool.get("assetIds");
        Map<String, Map<String, Object>> found = new HashMap<>();
        for (var row : marketRepository.ownedMembers(assetIds, user)) {
            require(
                    DOMESTIC_MARKETS.contains(str(row.get("market")).toUpperCase(Locale.ROOT)),
                    "首版仅支持国内市场标的");
            row.put("assetClass", assetClass(row));
            found.put(str(row.get("id")), row);
        }
        require(found.size() == assetIds.size(), "资产池中有标的已不存在或不属于当前用户");
        return assetIds.stream().map(identity -> found.get(str(identity))).toList();
    }

    public List<Map<String, Object>> snapshot(Long u, Map<String, Object> pool, String end) {
        return snapshot(members(u, pool), null, end);
    }

    public List<Map<String, Object>> snapshot(
            List<Map<String, Object>> selected, String start, String end) {
        List<Map<String, Object>> result = new ArrayList<>();
        require(selected.size() <= 100, "当前任务最多运行100个标的；完整资产池可供后续因子研究读取");
        List<Long> ids =
                selected.stream()
                        .map(member -> Long.valueOf(str(member.get("product_id"))))
                        .distinct()
                        .toList();
        Set<String> adjustments = new LinkedHashSet<>(Set.of("NONE"));
        for (var member : selected)
            adjustments.add(
                    dataQualityService.adjustType(
                            str(member.get("product_type")), str(member.get("market"))));
        var quoteGroups =
                marketData.readDailyQuotes(
                        ids,
                        start == null ? null : LocalDate.parse(start),
                        LocalDate.parse(end),
                        adjustments);
        for (var member : selected) {
            var quotes =
                    quoteGroups.getOrDefault(
                            Long.valueOf(str(member.get("product_id"))), List.of());
            Map<LocalDate, ProductDailyQuote> adjusted = new HashMap<>();
            String requiredAdjust =
                    dataQualityService.adjustType(
                            str(member.get("product_type")), str(member.get("market")));
            for (var quote : quotes)
                if (requiredAdjust.equals(quote.getAdjustType()))
                    adjusted.put(quote.getTradeDate(), quote);
            List<Map<String, Object>> bars = new ArrayList<>();
            for (var quote : quotes) {
                if (!"NONE".equals(quote.getAdjustType())) continue;
                String day = quote.getTradeDate().toString();
                Map<String, Object> bar = new LinkedHashMap<>();
                bar.put("date", day);
                bar.put("open", quote.getOpenPrice());
                bar.put("close", quote.getClosePrice());
                bar.put("high", quote.getHighPrice());
                bar.put("low", quote.getLowPrice());
                bar.put("previousClose", quote.getPreviousClose());
                var researchQuote = adjusted.get(quote.getTradeDate());
                Object research =
                        "FUND".equals(member.get("assetClass"))
                                ? quote.getTotalReturnIndex()
                                : "NONE".equals(requiredAdjust)
                                        ? quote.getClosePrice()
                                        : researchQuote == null
                                                ? null
                                                : researchQuote.getClosePrice();
                require(
                        research != null,
                        "研究行情缺少 "
                                + requiredAdjust
                                + " 序列或基金累计收益指数："
                                + str(member.get("code"))
                                + " "
                                + day);
                bar.put("researchClose", research);
                bar.put("volume", quote.getVolume());
                bar.put("amount", quote.getAmount());
                bar.put("adjustType", "NONE");
                bar.put("source", quote.getSource());
                bars.add(bar);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(
                    "id",
                    str(member.get("id") == null ? member.get("product_id") : member.get("id")));
            item.put("name", member.get("name"));
            item.put("code", member.get("code"));
            item.put("assetClass", member.get("assetClass"));
            item.put("corporateActionsVerified", false);
            item.put("bars", bars);
            result.add(item);
        }
        return result;
    }

    public List<LocalDate> commonObservedDates(
            Long userId, Map<String, Object> pool, LocalDate end) {
        Map<Long, String> adjustments = new LinkedHashMap<>();
        Set<Long> fundProducts = new LinkedHashSet<>();
        for (var member : members(userId, pool)) {
            Long productId = Long.valueOf(str(member.get("product_id")));
            adjustments.put(
                    productId,
                    dataQualityService.adjustType(
                            str(member.get("product_type")), str(member.get("market"))));
            if ("FUND".equals(member.get("assetClass"))) fundProducts.add(productId);
        }
        require(adjustments.size() <= 100, "当前任务最多运行100个标的；完整资产池可供后续因子研究读取");
        require(!adjustments.isEmpty(), "资产池至少包含一个标的");
        return marketData.commonObservedDates(adjustments, fundProducts, end);
    }
}
