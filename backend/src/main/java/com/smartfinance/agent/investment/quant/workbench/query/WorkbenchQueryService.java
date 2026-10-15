package com.smartfinance.agent.investment.quant.workbench.query;

import static com.smartfinance.agent.investment.quant.workbench.support.WorkbenchValues.*;

import com.smartfinance.agent.investment.quant.workbench.market.WorkbenchMarketService;
import com.smartfinance.agent.investment.quant.workbench.support.WorkbenchJson;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkbenchQueryService {
    private final WorkbenchQueryRepository queryRepository;
    private final WorkbenchJson json;
    private final WorkbenchResearchContext researchContext;
    private final WorkbenchMarketService market;

    public WorkbenchQueryService(
            WorkbenchQueryRepository queryRepository,
            WorkbenchJson json,
            WorkbenchResearchContext researchContext,
            WorkbenchMarketService market) {
        this.queryRepository = queryRepository;
        this.json = json;
        this.researchContext = researchContext;
        this.market = market;
    }

    public Map<String, Object> row(String table, Long user, String id) {
        List<Map<String, Object>> rows = queryRepository.findRecord(table, user, id);
        if (rows.isEmpty() || "DELETED".equals(rows.get(0).get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在、已删除或无权访问");
        return rows.get(0);
    }

    public Map<String, Object> object(Long u, String kind, String id) {
        Map<String, Object> r = row("quant_v2_object", u, id);
        if ("DELETED".equals(r.get("status")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录已删除");
        require(kind.equals(r.get("kind")), "记录类型不匹配");
        return r;
    }

    public Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> out = json.decode(r.get("payload"));
        for (var e : r.entrySet())
            if (!Set.of(
                            "payload",
                            "request_json",
                            "result_json",
                            "claim_token",
                            "lease_until",
                            "user_id")
                    .contains(e.getKey())) out.put(camel(e.getKey()), e.getValue());
        if ("strategies".equals(r.get("kind"))) {
            var latest = queryRepository.latestBacktest(r.get("user_id"), r.get("id"));
            out.put("latestBacktest", latest.isEmpty() ? null : view(latest.get(0)));
            var versions = queryRepository.latestVersion(r.get("user_id"), r.get("id"));
            out.put("latestVersion", versions.isEmpty() ? null : versions.get(0));
            out.put(
                    "deployments",
                    queryRepository.strategyDeployments(r.get("user_id"), r.get("id")));
        }
        if (r.containsKey("result_json")) {
            Map<String, Object> response = json.decode(r.get("result_json"));
            out.put("result", response.getOrDefault("result", Map.of()));
            out.put("qualification", response.getOrDefault("qualification", Map.of()));
            out.put("state", response.getOrDefault("state", Map.of()));
            out.put("modelRef", response.get("modelRef"));
            out.put("progress", "SUCCEEDED".equals(r.get("status")) ? 100 : null);
        }
        return out;
    }

    public List<Map<String, Object>> list(Long u, String kind) {
        String table =
                OBJECTS.contains(kind)
                        ? "quant_v2_object"
                        : TASKS.contains(kind)
                                ? "quant_v2_task"
                                : "deployments".equals(kind) ? "quant_v2_deployment" : null;
        require(table != null, "未知资源");
        return ("deployments".equals(kind)
                        ? queryRepository.listDeployments(table, u)
                        : queryRepository.listResources(table, u, kind))
                .stream().map(this::view).toList();
    }

    public Map<String, Object> get(Long u, String kind, String id) {
        if (OBJECTS.contains(kind)) return view(object(u, kind, id));
        if (TASKS.contains(kind)) {
            var r = row("quant_v2_task", u, id);
            require(kind.equals(r.get("kind")), "任务类型不匹配");
            var out = view(r);
            if ("backtests".equals(kind)) out.put("researchContext", researchContext.resolve(u, r));
            return out;
        }
        require("deployments".equals(kind), "未知资源");
        var out = view(row("quant_v2_deployment", u, id));
        var result = map(out.get("result"));
        var history = queryRepository.eventPayloads(u, id);
        for (String eventKind : List.of("orders", "fills", "cashLedger"))
            result.put(
                    eventKind,
                    history.stream()
                            .filter(e -> eventKind.equals(e.get("kind")))
                            .map(e -> json.decode(e.get("payload")))
                            .toList());
        out.put("result", result);
        return out;
    }

    public List<Map<String, Object>> versions(Long u, String kind, String identity) {
        object(u, kind, identity);
        return queryRepository.versions(u, identity).stream().map(this::view).toList();
    }

    public Map<String, Object> evaluationWindow(
            Long userId, String universeId, String startValue, String endValue) {
        var universe = object(userId, "universes", universeId);
        require(!"ARCHIVED".equals(universe.get("status")), "资产池已归档");
        LocalDate end = dateOrToday(endValue, "结束日期");
        LocalDate start = blank(startValue) ? null : dateOrToday(startValue, "开始日期");
        require(start == null || !start.isAfter(end), "开始日期不能晚于结束日期");
        var pool = json.decode(universe.get("payload"));
        List<LocalDate> shared = market.commonObservedDates(userId, pool, end);
        List<LocalDate> selected =
                start == null
                        ? shared
                        : shared.stream().filter(day -> !day.isBefore(start)).toList();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (int days : List.of(60, 120, 250)) {
            boolean ready = shared.size() >= days;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("days", days);
            item.put("ready", ready);
            item.put("startDate", ready ? shared.get(days - 1).toString() : null);
            item.put("endDate", shared.isEmpty() ? null : shared.get(0).toString());
            item.put("availableEvaluationDays", shared.size());
            suggestions.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minimumEvaluationDays", MINIMUM_EVALUATION_DAYS);
        result.put("availableEvaluationDays", shared.size());
        result.put("latestAvailableDate", shared.isEmpty() ? null : shared.get(0).toString());
        result.put("selectedEvaluationDays", start == null ? null : selected.size());
        result.put(
                "selectedRangeReady", start != null && selected.size() >= MINIMUM_EVALUATION_DAYS);
        result.put("suggestions", suggestions);
        return result;
    }

    private LocalDate dateOrToday(String value, String field) {
        if (blank(value)) return LocalDate.now();
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式错误");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public List<Map<String, Object>> events(Long u, String identity) {
        row("quant_v2_deployment", u, identity);
        return queryRepository.events(u, identity).stream().map(this::view).toList();
    }
}
