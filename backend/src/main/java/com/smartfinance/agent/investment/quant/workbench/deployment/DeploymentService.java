package com.smartfinance.agent.investment.quant.workbench.deployment;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.configuration.ConfigurationService;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchMarketService;
import com.smartfinance.agent.investment.quant.workbench.query.WorkbenchQueryService;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;
import com.smartfinance.agent.investment.quant.workbench.task.TaskService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DeploymentService {
    private final DeploymentRepository deploymentRepository;
    private final ConfigurationService configuration;
    private final WorkbenchQueryService queries;
    private final WorkbenchMarketService market;
    private final TaskService tasks;
    private final WorkbenchJson json;
    private final TransactionTemplate tx;

    public DeploymentService(
            DeploymentRepository deploymentRepository,
            ConfigurationService configuration,
            WorkbenchQueryService queries,
            WorkbenchMarketService market,
            TaskService tasks,
            WorkbenchJson json,
            PlatformTransactionManager manager) {
        this.deploymentRepository = deploymentRepository;
        this.configuration = configuration;
        this.queries = queries;
        this.market = market;
        this.tasks = tasks;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> deploy(Long u, Map<String, Object> b) {
        tasks.requireOrdinaryTask(u, str(b.get("backtestId")));
        return tx.execute(
                s -> {
                    var task = queries.row("quant_v2_task", u, str(b.get("backtestId")));
                    require(
                            "backtests".equals(task.get("kind"))
                                    && "SUCCEEDED".equals(task.get("status")),
                            "只能部署完成的回测");
                    var response = json.decode(task.get("result_json"));
                    require(
                            "QUALIFIED".equals(map(response.get("qualification")).get("status")),
                            "回测未通过资格验证，不能部署");
                    var strategy =
                            configuration.lockObject(u, "strategies", str(task.get("strategy_id")));
                    var deployRequest = json.decode(task.get("request_json"));
                    if (deployRequest.get("factorVersionId") != null) {
                        var fv =
                                queries.row(
                                        "quant_v2_version",
                                        u,
                                        str(deployRequest.get("factorVersionId")));
                        require(
                                !"ARCHIVED"
                                        .equals(
                                                configuration
                                                        .lockObject(
                                                                u,
                                                                "factors",
                                                                str(fv.get("object_id")))
                                                        .get("status")),
                                "因子集已归档");
                    }
                    var universe =
                            configuration.lockObject(u, "universes", str(task.get("universe_id")));
                    require(!"ARCHIVED".equals(universe.get("status")), "资产池已归档");
                    require(!"ARCHIVED".equals(strategy.get("status")), "策略已归档");
                    var req = json.decode(task.get("request_json"));
                    var effective = map(map(response.get("result")).get("provenance"));
                    var c = map(effective.getOrDefault("config", req.get("config")));
                    if (b.get("initialCash") != null) c.put("initialCash", b.get("initialCash"));
                    double cash = ((Number) c.getOrDefault("initialCash", 100000)).doubleValue();
                    require(Double.isFinite(cash) && cash > 0 && cash <= 1e12, "虚拟资金无效");
                    req.put("config", c);
                    req.put("kind", "PAPER");
                    String date = LocalDate.now().toString();
                    req.put("startDate", date);
                    req.put("endDate", date);
                    req.put("assets", market.snapshot(u, map(req.get("universe")), date));
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("cash", cash);
                    state.put("positions", Map.of());
                    state.put("pendingOrders", List.of());
                    state.put("receivables", List.of());
                    state.put("lastDate", "");
                    state.put("sessionCount", 0);
                    state.put("equityCurve", List.of());
                    req.put("state", state);
                    String key = id(), name = str(b.getOrDefault("name", task.get("name")));
                    require(!name.isBlank() && name.length() <= 160, "组合名称必填且不超过160字");
                    deploymentRepository.insert(
                            key,
                            u,
                            name,
                            task.get("id"),
                            task.get("strategy_id"),
                            task.get("universe_id"),
                            "RUNNING",
                            1,
                            json.encode(req),
                            json.encode(Map.of("state", state)),
                            System.currentTimeMillis(),
                            now(),
                            now());
                    return queries.get(u, "deployments", key);
                });
    }

    public Map<String, Object> lifecycle(
            Long u, String identity, String action, Map<String, Object> b) {
        return tx.execute(
                s -> {
                    deploymentRepository.lock(identity, u);
                    var r = queries.row("quant_v2_deployment", u, identity);
                    String status = str(r.get("status"));
                    require(!"STOPPED".equals(status), "组合已停止");
                    if ("pause".equals(action)) require("RUNNING".equals(status), "只能暂停运行中的组合");
                    if ("resume".equals(action)) require("PAUSED".equals(status), "只能恢复已暂停的组合");
                    String next =
                            switch (action) {
                                case "pause" -> "PAUSED";
                                case "resume" -> "RUNNING";
                                case "stop" -> {
                                    String mode = str(b.get("mode"));
                                    require(
                                            Set.of("KEEP", "LIQUIDATE").contains(mode),
                                            "请选择保留持仓或正常清仓");
                                    yield "KEEP".equals(mode) ? "STOPPED" : "STOPPING";
                                }
                                default ->
                                        throw new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, "未知操作");
                            };
                    require(!"STOPPING".equals(status), "组合正在按正常规则清仓");
                    var result = json.decode(r.get("result_json"));
                    var state = map(result.get("state"));
                    if (!"RUNNING".equals(next)) {
                        if (state.get("pendingOrders") instanceof List<?> pending)
                            for (Object order : pending) {
                                var cancelled = map(order);
                                cancelled.put("status", "CANCELLED");
                                cancelled.put("reason", "组合暂停或停止");
                                String eventKey =
                                        "cancel:" + identity + ":" + r.get("revision") + ":" + id();
                                deploymentRepository.insertEvent(
                                        id(),
                                        u,
                                        identity,
                                        eventKey,
                                        "orders",
                                        json.encode(cancelled),
                                        now());
                            }
                        state.put("pendingOrders", List.of());
                        result.put("state", state);
                    }
                    var request = json.decode(r.get("request_json"));
                    if ("resume".equals(action))
                        request.put("signalStartDate", LocalDate.now().toString());
                    require(
                            deploymentRepository.transition(
                                            next,
                                            json.encode(result),
                                            json.encode(request),
                                            System.currentTimeMillis(),
                                            now(),
                                            identity,
                                            u,
                                            r.get("revision"))
                                    == 1,
                            "组合状态已变化，请刷新");
                    var event =
                            Map.of(
                                    "action",
                                    action,
                                    "previousStatus",
                                    status,
                                    "status",
                                    next,
                                    "effectiveAt",
                                    now());
                    deploymentRepository.insertEvent(
                            id(),
                            u,
                            identity,
                            "lifecycle:" + r.get("revision"),
                            "lifecycle",
                            json.encode(event),
                            now());
                    return queries.get(u, "deployments", identity);
                });
    }

    public void delete(Long user, String identity) {
        tx.executeWithoutResult(
                status -> {
                    deploymentRepository.lockForDeletion(user, identity);
                    var record = queries.row("quant_v2_deployment", user, identity);
                    require("STOPPED".equals(record.get("status")), "请先停止模拟组合，等待清仓和结算完成后再删除");
                    deploymentRepository.delete(user, identity, now());
                });
    }
}
