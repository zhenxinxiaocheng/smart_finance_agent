package com.smartfinance.agent.investment.quant.workbench.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorkbenchValues {
    private WorkbenchValues() {}

    public static String now() {
        return Instant.now().toString();
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static String camel(String s) {
        StringBuilder b = new StringBuilder();
        boolean upper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') upper = true;
            else {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?>
                ? new LinkedHashMap<>((Map<String, Object>) value)
                : new LinkedHashMap<>();
    }

    public static final Set<String> OBJECTS = Set.of("universes", "factors", "strategies");
    public static final Set<String> TASKS = Set.of("training-runs", "backtests", "factor-runs");
    public static final Set<String> DOMESTIC_MARKETS = Set.of("SSE", "SZSE", "BSE", "FUND_CN");
    public static final int MINIMUM_EVALUATION_DAYS = 60;
}
