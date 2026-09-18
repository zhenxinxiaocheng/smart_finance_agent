package com.smartfinance.agent.investment.quant.workbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfinance.agent.investment.quant.BenchmarkProfile;
import com.smartfinance.agent.investment.quant.QuantBenchmarkProfileService;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class WorkbenchTrackingIndex {
    private final QuantBenchmarkProfileService profiles;
    private final ObjectMapper json;
    public WorkbenchTrackingIndex(QuantBenchmarkProfileService profiles, ObjectMapper json) {
        this.profiles = profiles; this.json = json;
    }

    public Map<String,Object> resolve(List<Map<String,Object>> assets, LocalDate start, LocalDate end) {
        // Optional comparison: no provider requests, guessed stock indices or composites.
        try {
            BenchmarkProfile selected = null;
            for (var asset : assets) {
                String type = String.valueOf(asset.get("product_type")), code = String.valueOf(asset.get("code"));
                if (!Set.of("MUTUAL_FUND", "FUND", "ETF").contains(type)) return Map.of();
                var profile = profiles.configuration(type, code, start);
                if (profile == null || !code.equals(profile.getProductCode())) return Map.of();
                String family = Objects.toString(profile.getModelFamily(), "");
                if (!(family.equals("INDEX_FUND") || family.endsWith("_INDEX_FUND"))) return Map.of();
                if (!"CNY".equals(profile.getCurrency()) || profile.getDisplayName() == null || profile.getDisplayName().isBlank()) return Map.of();
                if (profile.getEffectiveTo() != null && profile.getEffectiveTo().isBefore(end)) return Map.of();
                Map<String,BigDecimal> components = json.readValue(profile.getCompositionJson(), new TypeReference<>() {});
                if (components.size() != 1 || !BigDecimal.ONE.equals(components.getOrDefault(profile.getBenchmarkCode(), BigDecimal.ZERO).stripTrailingZeros())) return Map.of();
                if (selected != null && (!Objects.equals(selected.getBenchmarkCode(), profile.getBenchmarkCode())
                    || !Objects.equals(selected.getFxRule(), profile.getFxRule()))) return Map.of();
                selected = profile;
            }
            if (selected == null) return Map.of();
            var first = assets.get(0);
            var resolved = profiles.resolveCachedComparison(String.valueOf(first.get("product_type")), String.valueOf(first.get("code")), start, end);
            if (!resolved.available()) return Map.of();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("status", "READY"); result.put("code", resolved.benchmarkCode());
            result.put("name", selected.getDisplayName()); result.put("sourceVersion", resolved.sourceVersion());
            result.put("records", resolved.records());
            return result;
        } catch (Exception unavailable) {
            return Map.of();
        }
    }
}
