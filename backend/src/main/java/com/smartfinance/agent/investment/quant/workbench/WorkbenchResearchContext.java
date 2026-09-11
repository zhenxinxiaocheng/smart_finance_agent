package com.smartfinance.agent.investment.quant.workbench;

import java.util.*;
import static com.smartfinance.agent.investment.quant.workbench.WorkbenchService.*;

/** Read-only projection of a task's frozen inputs, never of today's market data. */
final class WorkbenchResearchContext {
    private final WorkbenchService service;

    WorkbenchResearchContext(WorkbenchService service) { this.service = service; }

    Map<String,Object> resolve(Long user, Map<String,Object> task) {
        var warnings = new ArrayList<Map<String,Object>>();
        var request = service.decode(task.get("request_json"));
        var result = map(service.decode(task.get("result_json")).get("result"));
        var provenance = map(result.get("provenance"));
        String strategyId = str(task.get("strategy_id"));
        String strategyVersion = str(task.get("strategy_version_id"));
        String universeId = str(task.get("universe_id"));
        String universeVersion = str(request.getOrDefault("universeVersionId", request.get("universeVersion")));
        String factorVersion = str(request.getOrDefault("factorVersionId", request.get("factorSetVersionId")));
        conflict(warnings, "strategyVersionId", strategyVersion, request.get("strategyVersionId"));
        conflict(warnings, "universeId", universeId, request.get("universeId"));
        conflict(warnings, "universeVersion", universeVersion, request.get("universeVersion"));
        conflict(warnings, "factorSetVersionId", factorVersion, request.get("factorSetVersionId"));
        conflict(warnings, "provenance.strategyId", strategyId, provenance.get("strategyId"));
        conflict(warnings, "provenance.strategyVersionId", strategyVersion, provenance.get("strategyVersionId"));
        conflict(warnings, "provenance.universeId", universeId, provenance.get("universeId"));
        conflict(warnings, "provenance.universeVersion", universeVersion, provenance.get("universeVersion"));
        conflict(warnings, "provenance.factorSetVersionId", factorVersion, provenance.get("factorSetVersionId"));
        var strategy = version(user, "strategies", strategyId, strategyVersion, warnings);
        var strategySnapshot = map(strategy.get("snapshot"));
        conflict(warnings, "strategy.universeId", universeId, strategySnapshot.get("universeId"));
        String factorId = str(strategySnapshot.get("factorSetId"));
        var lineage = new LinkedHashMap<String,Object>();
        lineage.put("strategy", strategy);
        lineage.put("universe", version(user, "universes", universeId, universeVersion, warnings));
        if (!factorId.isBlank() || !factorVersion.isBlank()) {
            lineage.put("factorSet", version(user, "factors", factorId, factorVersion, warnings));
        }
        var context = new LinkedHashMap<String,Object>();
        context.put("lineage", lineage);
        context.put("dataSnapshot", dataSnapshot(request.get("assets")));
        context.put("requestConfig", map(request.get("config")));
        var range = new LinkedHashMap<String,Object>();
        range.put("startDate", request.get("startDate"));
        range.put("endDate", request.get("endDate"));
        context.put("requestedRange", range);
        for (String key : List.of("modelRef", "modelTaskId")) {
            if (!str(request.get(key)).isBlank()) context.put(key, request.get(key));
            conflict(warnings, "provenance." + key, str(request.get(key)), provenance.get(key));
            conflict(warnings, "result." + key, str(request.get(key)), result.get(key));
        }
        context.put("warnings", warnings);
        return context;
    }

    private Map<String,Object> version(Long user, String kind, String objectId, String versionId,
                                       List<Map<String,Object>> warnings) {
        var out = new LinkedHashMap<String,Object>();
        out.put("objectId", objectId.isBlank() ? null : objectId);
        out.put("versionId", versionId.isBlank() ? null : versionId);
        out.put("state", "UNAVAILABLE");
        if (versionId.isBlank()) {
            warning(warnings, "VERSION_NOT_RECORDED", kind, "未记录冻结版本");
            return out;
        }
        var versions = service.db.queryForList("SELECT * FROM quant_v2_version WHERE user_id=? AND id=?", user, versionId);
        if (versions.isEmpty()) {
            warning(warnings, "VERSION_UNAVAILABLE", kind, "冻结版本不存在或不可访问");
            return out;
        }
        var version = versions.get(0);
        String owner = str(version.get("object_id"));
        if (!objectId.isBlank() && !objectId.equals(owner)) {
            warning(warnings, "VERSION_REFERENCE_CONFLICT", kind, "冻结版本不属于所引用的对象");
            return out;
        }
        // Soft-deleted objects retain their kind and ownership, and may be read via this task only.
        var objects = service.db.queryForList("SELECT kind,status FROM quant_v2_object WHERE user_id=? AND id=?", user, owner);
        if (objects.isEmpty() || !kind.equals(objects.get(0).get("kind"))) {
            warning(warnings, "VERSION_OBJECT_UNAVAILABLE", kind, "无法核对冻结版本所属对象的类型");
            return out;
        }
        var snapshot = service.decode(version.get("payload"));
        if ((!str(snapshot.get("id")).isBlank() && !owner.equals(str(snapshot.get("id"))))
                || (snapshot.get("revision") != null && !str(snapshot.get("revision")).equals(str(version.get("version"))))) {
            warning(warnings, "VERSION_REFERENCE_CONFLICT", kind, "冻结快照与版本记录不一致");
            return out;
        }
        out.put("state", "AVAILABLE");
        out.put("objectId", owner);
        out.put("version", version.get("version"));
        out.put("name", snapshot.get("name"));
        out.put("snapshot", snapshot);
        out.put("currentStatus", objects.get(0).get("status"));
        return out;
    }

    private List<Map<String,Object>> dataSnapshot(Object value) {
        var out = new ArrayList<Map<String,Object>>();
        if (!(value instanceof List<?> assets)) return out;
        for (Object entry : assets) {
            var asset = map(entry);
            var item = new LinkedHashMap<String,Object>();
            for (String key : List.of("id", "name", "code", "assetClass")) item.put(key, asset.get(key));
            var dates = new TreeSet<String>();
            var sources = new TreeSet<String>();
            var adjustments = new TreeSet<String>();
            int observations = 0;
            if (asset.get("bars") instanceof List<?> bars) for (Object raw : bars) {
                var bar = map(raw);
                if (!str(bar.get("date")).isBlank()) { dates.add(str(bar.get("date"))); observations++; }
                if (!str(bar.get("source")).isBlank()) sources.add(str(bar.get("source")));
                if (!str(bar.get("adjustType")).isBlank()) adjustments.add(str(bar.get("adjustType")));
            }
            item.put("startDate", dates.isEmpty() ? null : dates.first());
            item.put("endDate", dates.isEmpty() ? null : dates.last());
            item.put("observations", observations);
            item.put("sources", new ArrayList<>(sources));
            item.put("adjustTypes", new ArrayList<>(adjustments));
            out.add(item);
        }
        return out;
    }

    private void conflict(List<Map<String,Object>> warnings, String source, String expected, Object actual) {
        if (!str(actual).isBlank() && !expected.isBlank() && !expected.equals(str(actual)))
            warning(warnings, "SOURCE_REFERENCE_CONFLICT", source, "结果、请求或冻结引用不一致，请核对原始记录");
    }

    private void warning(List<Map<String,Object>> warnings, String code, String source, String message) {
        warnings.add(Map.of("code", code, "source", source, "message", message));
    }
}
