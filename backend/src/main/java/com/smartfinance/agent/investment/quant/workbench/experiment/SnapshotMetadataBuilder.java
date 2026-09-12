package com.smartfinance.agent.investment.quant.workbench.experiment;

import java.util.*;

/** Metadata is a deterministic projection of the data artifact, never of its consumers. */
public final class SnapshotMetadataBuilder {
    private SnapshotMetadataBuilder() {}
    public static Map<String,Object> build(List<Map<String,Object>> assets) {
        List<Map<String,Object>> items = new ArrayList<>();
        SortedSet<String> overall = new TreeSet<>();
        for (var asset : assets) {
            Map<String,Object> item = new LinkedHashMap<>();
            for (String key : List.of("id","code","name","assetClass"))
                if (asset.get(key) != null) item.put(key, asset.get(key));
            SortedSet<String> dates = new TreeSet<>(), sources = new TreeSet<>(), adjust = new TreeSet<>();
            add(sources, asset.get("source")); add(adjust, asset.get("adjustType"));
            var bars = asset.get("bars") instanceof List<?> list ? list : List.of();
            for (Object value : bars) if (value instanceof Map<?,?> bar) {
                add(dates, bar.get("date")); add(sources, bar.get("source")); add(adjust, bar.get("adjustType"));
            }
            item.put("observations", bars.size()); item.put("sources", List.copyOf(sources));
            item.put("adjustTypes", List.copyOf(adjust));
            if (!dates.isEmpty()) { item.put("startDate", dates.first()); item.put("endDate", dates.last()); }
            overall.addAll(dates); items.add(item);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("assetCount", assets.size()); result.put("assets", items);
        if (!overall.isEmpty()) { result.put("startDate", overall.first()); result.put("endDate", overall.last()); }
        return result;
    }
    private static void add(Set<String> set, Object value) { if (value != null && !value.toString().isBlank()) set.add(value.toString()); }
}
