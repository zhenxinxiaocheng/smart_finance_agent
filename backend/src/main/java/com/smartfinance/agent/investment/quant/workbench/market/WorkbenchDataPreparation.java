package com.smartfinance.agent.investment.quant.workbench.market;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.entity.InvestmentProduct;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchAnalysisClient;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchExecutionException;
import com.smartfinance.agent.investment.quant.workbench.deployment.DeploymentRepository;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;
import com.smartfinance.agent.investment.quant.workbench.task.TaskRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkbenchDataPreparation {
    private final com.smartfinance.agent.investment.service.MarketDataRequirementService
            requirements;
    private final com.smartfinance.agent.investment.service.MarketDataDemandService demand;
    private final WorkbenchAnalysisClient client;
    private final com.smartfinance.agent.investment.config.InvestmentHorizonProperties properties;
    private final com.smartfinance.agent.investment.service.InvestmentHistoryPreparationService
            history;
    private final WorkbenchMarketService market;
    private final WorkbenchTrackingIndex trackingIndex;
    private final TaskRepository taskRepository;
    private final DeploymentRepository deploymentRepository;
    private final WorkbenchJson json;
    private final TransactionTemplate tx;

    public WorkbenchDataPreparation(
            com.smartfinance.agent.investment.service.MarketDataRequirementService requirements,
            com.smartfinance.agent.investment.service.MarketDataDemandService demand,
            WorkbenchAnalysisClient client,
            com.smartfinance.agent.investment.config.InvestmentHorizonProperties properties,
            com.smartfinance.agent.investment.service.InvestmentHistoryPreparationService history,
            WorkbenchMarketService market,
            WorkbenchTrackingIndex trackingIndex,
            TaskRepository taskRepository,
            DeploymentRepository deploymentRepository,
            WorkbenchJson json,
            PlatformTransactionManager manager) {
        this.requirements = requirements;
        this.demand = demand;
        this.client = client;
        this.properties = properties;
        this.history = history;
        this.market = market;
        this.trackingIndex = trackingIndex;
        this.taskRepository = taskRepository;
        this.deploymentRepository = deploymentRepository;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    @org.springframework.beans.factory.annotation.Value(
            "${market-data.demand.preparation-check-ms:15000}")
    private long checkMs = 15000;

    @org.springframework.beans.factory.annotation.Value(
            "${market-data.demand.preparation-timeout:24h}")
    private Duration timeout = Duration.ofHours(24);

    public void initialize(Map<String, Object> request, List<Map<String, Object>> members) {
        require(!members.isEmpty() && members.size() <= 100, "当前任务需包含1至100个标的");
        validatePreparation(members);
        var dependency = client.dataRequirements(map(request.get("config")));
        require(List.of("PRICE").equals(dependency.get("datasets")), "当前数据依赖尚不支持自动准备");
        request.put("config", dependency.get("config"));
        int warmup = ((Number) dependency.get("warmupTradingDays")).intValue();
        long calendar =
                (long)
                                Math.ceil(
                                        warmup
                                                * (double) properties.getCalendarDaysPerYear()
                                                / properties.getTradingDaysPerYear())
                        + properties.getCalendarBufferDays();
        request.put(
                "dataStartDate",
                LocalDate.parse(str(request.get("startDate"))).minusDays(calendar).toString());
        request.put("warmupCalendarDays", calendar);
        request.put("preparedMembers", members);
        request.put("dataPreparation", true);
        request.put("assets", List.of());
    }

    private void validatePreparation(Collection<Map<String, Object>> members) {
        for (var member : members) {
            require(
                    DOMESTIC_MARKETS.contains(str(member.get("market")).toUpperCase(Locale.ROOT)),
                    "首版仅支持国内市场标的");
            var product = new InvestmentProduct();
            product.setProductType(str(member.get("product_type")));
            product.setMarket(str(member.get("market")));
            product.setFundCategory(str(member.get("fund_category")));
            require(history.supportsDemandPreparation(product), "该标的当前不支持完整区间准备，请先核对基金分类或调整资产池");
        }
    }

    private void validateCurrentMembers(List<Map<String, Object>> members) {
        var ids =
                members.stream()
                        .map(row -> ((Number) row.get("product_id")).longValue())
                        .distinct()
                        .toList();
        var current = market.productsById(ids);
        require(current.size() == ids.size(), "资产池中有市场证券已不存在");
        validatePreparation(current.values());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members(Map<String, Object> request) {
        return ((List<?>) request.get("preparedMembers"))
                .stream()
                        .map(
                                com.smartfinance.agent.investment.quant.workbench.support
                                                .WorkbenchValues
                                        ::map)
                        .toList();
    }

    public void register(
            long user,
            String source,
            String identity,
            Map<String, Object> request,
            boolean followLatest) {
        register(user, source, identity, request, followLatest, members(request));
    }

    private void register(
            long user,
            String source,
            String identity,
            Map<String, Object> request,
            boolean followLatest,
            List<Map<String, Object>> selected) {
        LocalDate start = LocalDate.parse(str(request.get("dataStartDate"))),
                end = LocalDate.parse(str(request.get("endDate")));
        for (var member : selected) {
            long product = ((Number) member.get("product_id")).longValue();
            LocalDate first = demand.boundedStart(product, start);
            require(!first.isAfter(end), "所选区间早于标的上市或成立日期");
            requirements.save(
                    user,
                    source,
                    identity,
                    product,
                    "PRICE",
                    "REQUIRED",
                    first,
                    end,
                    followLatest,
                    null);
        }
    }

    /** Runs under the task lease; waiting yields its slot so another ready task can execute. */
    public boolean prepare(Map<String, Object> task, String claim) {
        var request = json.decode(task.get("request_json"));
        if (!Boolean.TRUE.equals(request.get("dataPreparation"))) return true;
        validateCurrentMembers(members(request));
        long user = ((Number) task.get("user_id")).longValue();
        String identity = str(task.get("id"));
        if (Instant.parse(str(task.get("created_at"))).plus(timeout).isBefore(Instant.now()))
            throw new WorkbenchExecutionException("DATA_PREPARATION_TIMEOUT", "数据准备暂未完成，可稍后重试");
        LocalDate end = LocalDate.parse(str(request.get("endDate")));
        boolean ready =
                members(request).stream()
                        .allMatch(
                                member ->
                                        demand.prepared(
                                                user,
                                                "QUANT_TASK",
                                                identity,
                                                ((Number) member.get("product_id")).longValue(),
                                                end));
        if (!ready) {
            taskRepository.waitForData(
                    System.currentTimeMillis() + checkMs, now(), identity, claim);
            return false;
        }
        var assets =
                market.snapshot(
                        members(request), str(request.get("dataStartDate")), end.toString());
        Set<String> common = null;
        for (var asset : assets) {
            Set<String> dates = new HashSet<>();
            for (Object item : (List<?>) asset.get("bars")) {
                String date = str(map(item).get("date"));
                if (date.compareTo(str(request.get("startDate"))) >= 0) dates.add(date);
            }
            if (common == null) common = dates;
            else common.retainAll(dates);
        }
        if ("backtests".equals(task.get("kind")))
            require(
                    common != null && common.size() >= MINIMUM_EVALUATION_DAYS,
                    "已获取可用历史，但所选区间不足最低评估天数，请调整区间");
        request.put("assets", assets);
        request.put("dataPreparation", false);
        if ("backtests".equals(task.get("kind"))) {
            var comparison =
                    trackingIndex.resolve(
                            members(request), LocalDate.parse(str(request.get("startDate"))), end);
            if (!comparison.isEmpty()) request.put("trackingIndex", comparison);
        }
        int changed =
                taskRepository.savePreparedRequest(
                        json.encode(request), identity, claim, System.currentTimeMillis());
        if (changed != 1) return false;
        task.put("request_json", json.encode(request));
        return true;
    }

    public boolean prepareDeployment(Map<String, Object> deployment, String claim) {
        var request = json.decode(deployment.get("request_json"));
        long user = ((Number) deployment.get("user_id")).longValue();
        String identity = str(deployment.get("id"));
        if (!request.containsKey("preparedMembers")) {
            Object frozen = request.get("assets");
            initialize(request, market.members(user, map(request.get("universe"))));
            request.put("assets", frozen);
        }
        LocalDate end = requirements.now().toLocalDate();
        request.put("endDate", end.toString());
        LocalDate start = end.minusDays(((Number) request.get("warmupCalendarDays")).longValue());
        var state = map(json.decode(deployment.get("result_json")).get("state"));
        String last = str(state.get("lastDate"));
        if (!last.isBlank() && LocalDate.parse(last).isBefore(start)) start = LocalDate.parse(last);
        request.put("dataStartDate", start.toString());
        var selected = deploymentMembers(request, state, str(deployment.get("status")));
        validateCurrentMembers(selected);
        Boolean owned =
                tx.execute(
                        tx -> {
                            if (deploymentRepository.lockClaim(
                                            identity,
                                            claim,
                                            deployment.get("revision"),
                                            System.currentTimeMillis())
                                    != 1) return false;
                            requirements.release(user, "DEPLOYMENT", identity);
                            register(user, "DEPLOYMENT", identity, request, true, selected);
                            return true;
                        });
        if (!Boolean.TRUE.equals(owned)) return false;
        LocalDate through = end;
        for (var member : selected) {
            LocalDate published =
                    demand.publishedThrough(
                            user,
                            "DEPLOYMENT",
                            identity,
                            ((Number) member.get("product_id")).longValue());
            if (published == null) {
                through = null;
                break;
            }
            if (published.isBefore(through)) through = published;
        }
        if (through == null) {
            deploymentRepository.waitForData(
                    System.currentTimeMillis() + checkMs,
                    now(),
                    identity,
                    claim,
                    deployment.get("revision"));
            return false;
        }
        // Keep the frozen universe/binding. Refresh only relevant assets; inactive bars stay
        // frozen.
        var fresh =
                selected.isEmpty()
                        ? List.<Map<String, Object>>of()
                        : market.snapshot(selected, start.toString(), through.toString());
        Map<String, Map<String, Object>> assets = new LinkedHashMap<>();
        if (request.get("assets") instanceof List<?> previous)
            for (var item : previous) {
                var asset = map(item);
                assets.put(str(asset.get("id")), asset);
            }
        for (var asset : fresh) assets.put(str(asset.get("id")), asset);
        require(assets.size() == members(request).size(), "模拟组合缺少初始行情快照，请重新部署");
        request.put("assets", new ArrayList<>(assets.values()));
        request.put("dataPreparation", false);
        int saved =
                deploymentRepository.savePreparedRequest(
                        json.encode(request),
                        identity,
                        claim,
                        deployment.get("revision"),
                        System.currentTimeMillis());
        if (saved != 1) return false;
        deployment.put("request_json", json.encode(request));
        return true;
    }

    private List<Map<String, Object>> deploymentMembers(
            Map<String, Object> request, Map<String, Object> state, String status) {
        if ("RUNNING".equals(status)) return members(request);
        Set<String> relevant = new HashSet<>();
        for (var position : map(state.get("positions")).entrySet()) {
            Object quantity =
                    position.getValue() instanceof Number
                            ? position.getValue()
                            : map(position.getValue()).getOrDefault("quantity", 0);
            if (quantity instanceof Number number && number.doubleValue() > 0)
                relevant.add(position.getKey());
        }
        for (String key : List.of("pendingOrders", "unsettledBuys"))
            if (state.get(key) instanceof List<?> entries)
                for (var item : entries) relevant.add(str(map(item).get("assetId")));
        return members(request).stream()
                .filter(
                        member ->
                                relevant.contains(
                                        str(
                                                member.get("id") == null
                                                        ? member.get("product_id")
                                                        : member.get("id"))))
                .toList();
    }
}
