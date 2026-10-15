package com.smartfinance.agent.investment.quant.workbench.configuration;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchMarketService;
import com.smartfinance.agent.investment.quant.workbench.query.WorkbenchQueryService;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ConfigurationService {
    private final ConfigurationRepository configurationRepository;
    private final WorkbenchQueryService queries;
    private final WorkbenchMarketService market;
    private final WorkbenchJson json;
    private final TransactionTemplate tx;

    public ConfigurationService(
            ConfigurationRepository configurationRepository,
            WorkbenchQueryService queries,
            WorkbenchMarketService market,
            WorkbenchJson json,
            PlatformTransactionManager manager) {
        this.configurationRepository = configurationRepository;
        this.queries = queries;
        this.market = market;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> lockObject(Long user, String kind, String identity) {
        configurationRepository.lock(user, identity, kind);
        return queries.object(user, kind, identity);
    }

    public Map<String, Object> save(
            Long u, String kind, String identity, Map<String, Object> body) {
        require(OBJECTS.contains(kind), "未知资源");
        return tx.execute(
                status -> {
                    String name = str(body.get("name")).trim();
                    require(!name.isEmpty() && name.length() <= 160, "名称必填且不超过160字");
                    Map<String, Object> payload = new LinkedHashMap<>(body);
                    for (String k :
                            List.of(
                                    "id",
                                    "status",
                                    "revision",
                                    "createdAt",
                                    "updatedAt",
                                    "name",
                                    "kind",
                                    "latestBacktest",
                                    "latestVersion",
                                    "deployments",
                                    "runningStatus")) payload.remove(k);
                    if ("universes".equals(kind)
                            && (payload.containsKey("productIds")
                                    || payload.containsKey("presetKey"))) {
                        String preset = str(payload.get("presetKey"));
                        if (!preset.isBlank())
                            payload.put("productIds", market.presetProducts(preset));
                        payload.remove("assetIds");
                        payload.put(
                                "membershipCapability",
                                preset.isBlank() ? "STATIC" : "CURRENT_SNAPSHOT");
                        payload.put("membershipAsOfDate", LocalDate.now().toString());
                    }
                    validateObject(u, kind, payload);
                    String key = identity == null ? id() : identity;
                    if (identity == null)
                        configurationRepository.insert(
                                key, u, kind, name, "DRAFT", 1, json.encode(payload), now(), now());
                    else {
                        var existing = lockObject(u, kind, key);
                        require(!"ARCHIVED".equals(existing.get("status")), "已归档记录不可编辑");
                        int revision = ((Number) existing.get("revision")).intValue();
                        if (body.containsKey("revision"))
                            require(
                                    ((Number) body.get("revision")).intValue() == revision,
                                    "记录已被修改，请刷新");
                        require(
                                configurationRepository.update(
                                                name, json.encode(payload), now(), key, u, revision)
                                        == 1,
                                "并发修改，请刷新");
                    }
                    Map<String, Object> saved = queries.get(u, kind, key);
                    if ("universes".equals(kind) && payload.containsKey("productIds"))
                        market.persistMembership(
                                u, key, ((Number) saved.get("revision")).intValue(), payload);
                    return saved;
                });
    }

    public void validateObject(Long u, String kind, Map<String, Object> p) {
        if ("universes".equals(kind)) {
            String assetClass = str(p.get("assetClass"));
            require(Set.of("STOCK", "ETF", "FUND").contains(assetClass), "请选择股票、ETF或场外基金");
            if (p.containsKey("productIds")) {
                List<Long> ids = market.productIds(p);
                require(!ids.isEmpty() && ids.size() <= 10000, "资产池需包含1至10000个标的");
                require(new HashSet<>(ids).size() == ids.size(), "资产不能重复");
                var products = market.productsById(ids);
                for (var product : products.values())
                    require(
                            DOMESTIC_MARKETS.contains(
                                    str(product.get("market")).toUpperCase(Locale.ROOT)),
                            "首版仅支持国内市场标的");
                for (var product : products.values())
                    require(assetClass.equals(market.assetClass(product)), "资产池不能混合资产类别");
                require(products.size() == ids.size(), "市场证券不存在");
                return;
            }
            require(
                    p.get("assetIds") instanceof List<?>
                            && !((List<?>) p.get("assetIds")).isEmpty()
                            && ((List<?>) p.get("assetIds")).size() <= 100,
                    "资产池需包含1至100个标的");
            Set<String> ids = new HashSet<>();
            for (Object id : (List<?>) p.get("assetIds")) require(ids.add(str(id)), "资产不能重复");
            for (var member : market.members(u, p))
                require(assetClass.equals(member.get("assetClass")), "资产池不能混合资产类别");
        } else if ("strategies".equals(kind)) {
            var pool = queries.object(u, "universes", str(p.get("universeId")));
            require(!"ARCHIVED".equals(pool.get("status")), "资产池已归档");
            var c = map(p.get("config"));
            if (c.containsKey("factors"))
                validateFactors(
                        c.get("factors"), str(json.decode(pool.get("payload")).get("assetClass")));
            require(
                    Set.of("TREND", "MULTI_FACTOR", "ML_ELASTIC_NET", "ML_XGBOOST")
                            .contains(str(c.get("strategyType"))),
                    "请选择有效策略模板");
            if (p.get("factorSetId") != null && !str(p.get("factorSetId")).isBlank()) {
                var f = queries.object(u, "factors", str(p.get("factorSetId")));
                require(!"ARCHIVED".equals(f.get("status")), "因子集已归档");
                require(
                        Objects.equals(
                                json.decode(f.get("payload")).get("assetClass"),
                                json.decode(pool.get("payload")).get("assetClass")),
                        "因子集与资产池类别不匹配");
            }
        } else {
            require(Set.of("STOCK", "ETF", "FUND").contains(str(p.get("assetClass"))), "请选择资产类别");
            validateFactors(p.get("factors"), str(p.get("assetClass")));
        }
    }

    public void validateFactors(Object value, String assetClass) {
        require(value instanceof List<?> && !((List<?>) value).isEmpty(), "请选择因子");
        Set<String> selected = new HashSet<>();
        boolean nonzero = false;
        for (Object item : (List<?>) value) {
            var factor = map(item);
            String key = str(factor.get("key"));
            require(
                    Set.of(
                                    "momentum",
                                    "trend",
                                    "volatility",
                                    "drawdown",
                                    "reversal",
                                    "volume",
                                    "liquidity")
                            .contains(key),
                    "不可用因子：" + key);
            require(selected.add(key), "因子不能重复");
            require(
                    !"FUND".equals(assetClass) || !Set.of("volume", "liquidity").contains(key),
                    "场外基金不能使用成交量或流动性因子");
            require(factor.getOrDefault("weight", 1) instanceof Number, "因子权重需为数字");
            double weight = ((Number) factor.getOrDefault("weight", 1)).doubleValue();
            require(Double.isFinite(weight) && Math.abs(weight) <= 100, "因子权重必须在-100至100之间");
            nonzero |= weight != 0;
        }
        require(nonzero, "至少一个因子权重不为0");
    }

    public Map<String, Object> copy(Long u, String kind, String identity) {
        var r = queries.get(u, kind, identity);
        r.put("name", str(r.get("name")) + " 副本");
        return save(u, kind, null, r);
    }

    public Map<String, Object> archive(Long u, String kind, String identity) {
        return tx.execute(
                s -> {
                    lockObject(u, kind, identity);
                    if ("factors".equals(kind)) {
                        for (String table : List.of("quant_v2_task", "quant_v2_deployment"))
                            for (var run : configurationRepository.activeRequests(table, u)) {
                                var req = json.decode(run.get("request_json"));
                                if (req.get("factorVersionId") != null) {
                                    var version =
                                            queries.row(
                                                    "quant_v2_version",
                                                    u,
                                                    str(req.get("factorVersionId")));
                                    require(
                                            !identity.equals(str(version.get("object_id"))),
                                            "请先停止关联任务和模拟组合");
                                }
                            }
                    }
                    String column = "strategies".equals(kind) ? "strategy_id" : "universe_id";
                    if (!"factors".equals(kind))
                        require(
                                configurationRepository.activeDeployments(column, u, identity) == 0,
                                "请先停止关联模拟组合");
                    require(
                            configurationRepository.activeTasks(u, identity, identity) == 0,
                            "请先取消关联任务");
                    configurationRepository.archive(now(), identity, u);
                    return queries.get(u, kind, identity);
                });
    }

    public String freeze(Long u, Map<String, Object> object) {
        String identity = str(object.get("id"));
        int rev = ((Number) object.get("revision")).intValue();
        var rows = configurationRepository.findVersion(u, identity, rev);
        if (!rows.isEmpty()) return str(rows.get(0).get("id"));
        String key = id();
        configurationRepository.insertVersion(
                key, u, identity, rev, json.encode(frozenPayload(object)), now());
        return key;
    }

    public Map<String, Object> frozenPayload(Map<String, Object> object) {
        Map<String, Object> value = json.decode(object.get("payload"));
        for (String key : List.of("id", "name", "revision", "status"))
            value.put(key, object.get(key));
        return value;
    }

    public void delete(Long user, String kind, String identity) {
        require(OBJECTS.contains(kind), "未知资源");
        tx.executeWithoutResult(
                status -> {
                    lockObject(user, kind, identity);
                    if (!"strategies".equals(kind)) {
                        String reference = "universes".equals(kind) ? "universeId" : "factorSetId";
                        for (var strategy : configurationRepository.activeStrategies(user)) {
                            require(
                                    !identity.equals(
                                            str(
                                                    json.decode(strategy.get("payload"))
                                                            .get(reference))),
                                    "策略“" + strategy.get("name") + "”仍在使用，请先更换配置、归档或删除该策略");
                        }
                    }
                    archive(user, kind, identity);
                    configurationRepository.delete(now(), identity, user);
                });
    }
}
