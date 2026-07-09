package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class ModelContextWindowRegistry {

    private static final Map<String, Integer> KNOWN_WINDOWS = Map.ofEntries(
            Map.entry("qwen3.6-flash", 131072),
            Map.entry("qwen3.5", 131072),
            Map.entry("qwen3", 131072),
            Map.entry("qwen-long", 1000000),
            Map.entry("qwen-max", 131072),
            Map.entry("qwen-plus", 131072),
            Map.entry("qwen-turbo", 131072)
    );

    public int resolve(String modelName, int fallback) {
        int safeFallback = Math.max(1, fallback);
        if (modelName == null || modelName.isBlank()) {
            return safeFallback;
        }
        String normalized = modelName.trim().toLowerCase(Locale.ROOT);
        Integer exact = KNOWN_WINDOWS.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> entry : KNOWN_WINDOWS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return safeFallback;
    }
}
