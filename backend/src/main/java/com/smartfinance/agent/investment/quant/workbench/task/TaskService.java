package com.smartfinance.agent.investment.quant.workbench.task;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.configuration.ConfigurationService;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchDataPreparation;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchMarketService;
import com.smartfinance.agent.investment.quant.workbench.query.WorkbenchQueryService;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskService {
    private final TaskRepository taskRepository;
    private final ConfigurationService configuration;
    private final WorkbenchQueryService queries;
    private final WorkbenchMarketService market;
    private final WorkbenchDataPreparation preparation;
    private final WorkbenchJson json;
    private final TransactionTemplate tx;

    public TaskService(
            TaskRepository taskRepository,
            ConfigurationService configuration,
            WorkbenchQueryService queries,
            WorkbenchMarketService market,
            WorkbenchDataPreparation preparation,
            WorkbenchJson json,
            PlatformTransactionManager manager) {
        this.taskRepository = taskRepository;
        this.configuration = configuration;
        this.queries = queries;
        this.market = market;
        this.preparation = preparation;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> createTask(Long u, String kind, Map<String, Object> b) {
        require(TASKS.contains(kind), "未知任务");
        return tx.execute(
                s -> {
                    String start = str(b.get("startDate")), end = str(b.get("endDate"));
                    try {
                        require(
                                !LocalDate.parse(start).isAfter(LocalDate.parse(end)),
                                "开始日期不能晚于结束日期");
                        require(!LocalDate.parse(end).isAfter(LocalDate.now()), "结束日期不能在未来");
                    } catch (java.time.format.DateTimeParseException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写正确日期");
                    }
                    Map<String, Object> config = map(b.get("config"));
                    String strategyId = null,
                            sv = null,
                            universeId = str(b.get("universeId")),
                            name = "因子研究";
                    Map<String, Object> req = new LinkedHashMap<>();
                    if (!"factor-runs".equals(kind)) {
                        var strategy =
                                configuration.lockObject(u, "strategies", str(b.get("strategyId")));
                        require(!"ARCHIVED".equals(strategy.get("status")), "策略已归档");
                        var p = json.decode(strategy.get("payload"));
                        strategyId = str(strategy.get("id"));
                        sv = configuration.freeze(u, strategy);
                        universeId = str(p.get("universeId"));
                        config = map(p.get("config"));
                        name = str(strategy.get("name"));
                        if (p.get("factorSetId") != null && !str(p.get("factorSetId")).isBlank()) {
                            var f =
                                    configuration.lockObject(
                                            u, "factors", str(p.get("factorSetId")));
                            require(!"ARCHIVED".equals(f.get("status")), "因子集已归档");
                            config.put("factors", json.decode(f.get("payload")).get("factors"));
                            req.put("factorVersionId", configuration.freeze(u, f));
                        }
                    }
                    var universe = configuration.lockObject(u, "universes", universeId);
                    require(!"ARCHIVED".equals(universe.get("status")), "资产池已归档");
                    var pool = json.decode(universe.get("payload"));
                    req.put("universeId", universeId);
                    req.put("universeVersionId", configuration.freeze(u, universe));
                    req.put("universeVersion", req.get("universeVersionId"));
                    config.put("assetClass", pool.get("assetClass"));
                    config.put("corporateActionsVerified", false);
                    if (b.get("initialCash") != null)
                        config.put("initialCash", b.get("initialCash"));
                    req.put(
                            "kind",
                            switch (kind) {
                                case "training-runs" -> "TRAINING";
                                case "backtests" -> "BACKTEST";
                                default -> "FACTOR_RESEARCH";
                            });
                    req.put("config", config);
                    req.put("startDate", start);
                    req.put("endDate", end);
                    req.put("strategyVersionId", sv);
                    req.put("universe", pool);
                    preparation.initialize(req, market.members(u, pool));
                    config = map(req.get("config"));
                    String modelTask = str(b.getOrDefault("modelTaskId", b.get("trainingRunId")));
                    if (!modelTask.isBlank()) {
                        var trained = queries.row("quant_v2_task", u, modelTask);
                        require(
                                "training-runs".equals(trained.get("kind"))
                                        && "SUCCEEDED".equals(trained.get("status")),
                                "请选择成功的训练结果");
                        var trainingRequest = json.decode(trained.get("request_json"));
                        var tc = map(trainingRequest.get("config"));
                        for (String key :
                                List.of(
                                        "strategyType",
                                        "assetClass",
                                        "predictionHorizon",
                                        "factors",
                                        "lookback",
                                        "slowWindow"))
                            require(Objects.equals(tc.get(key), config.get(key)), "模型配置不兼容：" + key);
                        var response = json.decode(trained.get("result_json"));
                        require(response.get("modelRef") != null, "训练没有有效模型产物");
                        req.put("modelRef", response.get("modelRef"));
                        req.put("modelTaskId", modelTask);
                    }
                    String key = id();
                    taskRepository.insert(
                            key,
                            u,
                            kind,
                            name,
                            "QUEUED",
                            "DATA_PREPARING",
                            strategyId,
                            sv,
                            universeId,
                            json.encode(req),
                            now(),
                            now());
                    preparation.register(u, "QUANT_TASK", key, req, false);
                    return queries.get(u, kind, key);
                });
    }

    public Map<String, Object> cancel(Long u, String kind, String identity) {
        requireOrdinaryTask(u, identity);
        queries.get(u, kind, identity);
        taskRepository.cancel(now(), identity, u);
        return queries.get(u, kind, identity);
    }

    public Map<String, Object> retry(Long u, String kind, String identity) {
        return tx.execute(
                txStatus -> {
                    requireOrdinaryTask(u, identity);
                    queries.get(u, kind, identity);
                    var old = queries.row("quant_v2_task", u, identity);
                    require(
                            Set.of("FAILED", "CANCELLED").contains(str(old.get("status"))),
                            "只能重试失败或取消的任务");
                    if (old.get("strategy_id") != null)
                        require(
                                !"ARCHIVED"
                                        .equals(
                                                configuration
                                                        .lockObject(
                                                                u,
                                                                "strategies",
                                                                str(old.get("strategy_id")))
                                                        .get("status")),
                                "策略已归档，不能重试");
                    var request = json.decode(old.get("request_json"));
                    if (request.get("factorVersionId") != null) {
                        var version =
                                queries.row(
                                        "quant_v2_version", u, str(request.get("factorVersionId")));
                        require(
                                !"ARCHIVED"
                                        .equals(
                                                configuration
                                                        .lockObject(
                                                                u,
                                                                "factors",
                                                                str(version.get("object_id")))
                                                        .get("status")),
                                "因子集已归档，不能重试");
                    }
                    require(
                            !"ARCHIVED"
                                    .equals(
                                            configuration
                                                    .lockObject(
                                                            u,
                                                            "universes",
                                                            str(old.get("universe_id")))
                                                    .get("status")),
                            "资产池已归档，不能重试");
                    String key = id();
                    taskRepository.insert(
                            key,
                            u,
                            kind,
                            old.get("name"),
                            "QUEUED",
                            "QUEUED",
                            old.get("strategy_id"),
                            old.get("strategy_version_id"),
                            old.get("universe_id"),
                            old.get("request_json"),
                            now(),
                            now());
                    if (Boolean.TRUE.equals(request.get("dataPreparation"))) {
                        preparation.register(u, "QUANT_TASK", key, request, false);
                        taskRepository.markPreparing(key);
                    }
                    return queries.get(u, kind, key);
                });
    }

    public void requireOrdinaryTask(Long user, String taskId) {
        require(taskRepository.experimentLinks(user, taskId) == 0, "实验任务请通过参数敏感性实验管理，不能独立操作或部署");
    }

    public void delete(Long user, String kind, String identity) {
        require(TASKS.contains(kind), "未知资源");
        requireOrdinaryTask(user, identity);
        tx.executeWithoutResult(
                status -> {
                    taskRepository.lockForDeletion(user, identity);
                    var record = queries.row("quant_v2_task", user, identity);
                    require(kind.equals(record.get("kind")), "任务类型不匹配");
                    require(
                            Set.of("SUCCEEDED", "FAILED", "CANCELLED")
                                    .contains(str(record.get("status"))),
                            "请先取消任务，等待任务结束后再删除");
                    taskRepository.delete(user, identity, now());
                });
    }
}
