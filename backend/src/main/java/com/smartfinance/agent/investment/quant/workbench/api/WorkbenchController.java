package com.smartfinance.agent.investment.quant.workbench.api;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.common.Result;
import com.smartfinance.agent.investment.quant.workbench.client.WorkbenchAnalysisClient;
import com.smartfinance.agent.investment.quant.workbench.configuration.ConfigurationService;
import com.smartfinance.agent.investment.quant.workbench.deployment.DeploymentService;
import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchMarketService;
import com.smartfinance.agent.investment.quant.workbench.query.WorkbenchQueryService;
import com.smartfinance.agent.investment.quant.workbench.task.TaskService;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/quant/v2")
public class WorkbenchController {
    private final WorkbenchQueryService queries;
    private final ConfigurationService configuration;
    private final TaskService tasks;
    private final DeploymentService deployments;
    private final WorkbenchMarketService market;
    private final WorkbenchAnalysisClient analysis;

    public WorkbenchController(
            WorkbenchQueryService queries,
            ConfigurationService configuration,
            TaskService tasks,
            DeploymentService deployments,
            WorkbenchMarketService market,
            WorkbenchAnalysisClient analysis) {
        this.queries = queries;
        this.configuration = configuration;
        this.tasks = tasks;
        this.deployments = deployments;
        this.market = market;
        this.analysis = analysis;
    }

    @GetMapping("/assets")
    public Result<?> assets(@RequestAttribute Long userId) {
        return Result.success(market.assets(userId));
    }

    @DeleteMapping("/{kind}/{id}")
    public Result<?> delete(
            @RequestAttribute Long userId, @PathVariable String kind, @PathVariable String id) {
        if (OBJECTS.contains(kind)) configuration.delete(userId, kind, id);
        else if (TASKS.contains(kind)) tasks.delete(userId, kind, id);
        else {
            require("deployments".equals(kind), "未知资源");
            deployments.delete(userId, id);
        }
        return Result.success();
    }

    @GetMapping("/factor-catalog")
    public Result<?> factors() {
        return Result.success(analysis.factors());
    }

    @GetMapping("/parameter-catalog")
    public Result<?> parameters() {
        return Result.success(analysis.parameters());
    }

    @GetMapping("/universes/{id}/evaluation-window")
    public Result<?> evaluationWindow(
            @RequestAttribute Long userId,
            @PathVariable String id,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return Result.success(queries.evaluationWindow(userId, id, startDate, endDate));
    }

    @GetMapping("/{kind}")
    public Result<?> list(@RequestAttribute Long userId, @PathVariable String kind) {
        return Result.success(queries.list(userId, kind));
    }

    @GetMapping("/{kind}/{id}")
    public Result<?> get(
            @RequestAttribute Long userId, @PathVariable String kind, @PathVariable String id) {
        return Result.success(queries.get(userId, kind, id));
    }

    @PostMapping("/{kind}")
    public Result<?> create(
            @RequestAttribute Long userId,
            @PathVariable String kind,
            @RequestBody Map<String, Object> body) {
        return Result.success(
                OBJECTS.contains(kind)
                        ? configuration.save(userId, kind, null, body)
                        : "deployments".equals(kind)
                                ? deployments.deploy(userId, body)
                                : tasks.createTask(userId, kind, body));
    }

    @PutMapping("/{kind}/{id}")
    public Result<?> update(
            @RequestAttribute Long userId,
            @PathVariable String kind,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return Result.success(configuration.save(userId, kind, id, body));
    }

    @GetMapping("/{kind}/{id}/versions")
    public Result<?> versions(
            @RequestAttribute Long userId, @PathVariable String kind, @PathVariable String id) {
        return Result.success(queries.versions(userId, kind, id));
    }

    @GetMapping("/deployments/{id}/events")
    public Result<?> events(@RequestAttribute Long userId, @PathVariable String id) {
        return Result.success(queries.events(userId, id));
    }

    @PostMapping("/{kind}/{id}/{action}")
    public Result<?> action(
            @RequestAttribute Long userId,
            @PathVariable String kind,
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> body) {
        Object result;
        if ("deployments".equals(kind))
            result = deployments.lifecycle(userId, id, action, body == null ? Map.of() : body);
        else if (OBJECTS.contains(kind))
            result =
                    switch (action) {
                        case "copy" -> configuration.copy(userId, kind, id);
                        case "archive" -> configuration.archive(userId, kind, id);
                        default -> throw new IllegalArgumentException("未知操作");
                    };
        else {
            require(TASKS.contains(kind), "未知资源");
            result =
                    switch (action) {
                        case "cancel" -> tasks.cancel(userId, kind, id);
                        case "retry" -> tasks.retry(userId, kind, id);
                        default -> throw new IllegalArgumentException("未知操作");
                    };
        }
        return Result.success(result);
    }
}
