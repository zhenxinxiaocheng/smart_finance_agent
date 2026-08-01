package com.smartfinance.agent.investment.quant;

import java.util.LinkedHashMap;
import java.util.Map;

final class QuantResearchUniverseRuleVersion {
    private QuantResearchUniverseRuleVersion() {
    }

    static boolean matches(Map<String, Object> stored,
                           Map<String, Object> configured,
                           String benchmarkCode,
                           String benchmarkSourceVersion) {
        Map<String, Object> expected = new LinkedHashMap<>(configured);
        expected.put("benchmarkCode", benchmarkCode);
        expected.put("benchmarkSourceVersion", benchmarkSourceVersion);
        return expected.equals(stored);
    }
}
