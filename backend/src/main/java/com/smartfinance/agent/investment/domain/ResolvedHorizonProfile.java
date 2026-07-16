package com.smartfinance.agent.investment.domain;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ResolvedHorizonProfile(String version,
                                     String templateVersion,
                                     List<HorizonSetting> settings,
                                     List<String> warnings) {

    public ResolvedHorizonProfile {
        version = requireText(version, "周期配置版本不能为空");
        templateVersion = requireText(templateVersion, "周期模板版本不能为空");
        settings = List.copyOf(Objects.requireNonNull(settings, "周期设置不能为空").stream()
                .sorted(Comparator.comparingInt(HorizonSetting::sortOrder))
                .toList());
        warnings = List.copyOf(Objects.requireNonNull(warnings, "周期警告不能为空"));
        if (settings.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个分析周期");
        }
        if (settings.stream().map(HorizonSetting::code).distinct().count() != settings.size()) {
            throw new IllegalArgumentException("周期代码不能重复");
        }
        if (settings.stream().filter(HorizonSetting::primary).count() > 1) {
            throw new IllegalArgumentException("只能设置一个主要周期");
        }
    }

    public Map<String, List<Integer>> analysisRanges() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        settings.forEach(item -> result.put(
                item.code(), List.of(item.minHoldingDays(), item.maxHoldingDays())));
        return Collections.unmodifiableMap(result);
    }

    public String primaryCode() {
        return settings.stream()
                .filter(HorizonSetting::primary)
                .findFirst()
                .orElse(settings.get(0))
                .code();
    }

    public int requiredHistoryDays() {
        return settings.stream().mapToInt(HorizonSetting::maxHoldingDays).max().orElseThrow();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
